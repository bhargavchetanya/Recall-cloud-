package dev.recall.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks a local Ollama model for one command line. Never throws and never waits longer than the timeout: when Ollama
 * is unreachable it logs once, behaves like no AI for a cool-down minute, then tries again.
 */
public final class OllamaFixSuggester implements FixSuggester {

    static final String EXPLANATION = "Suggested by the local AI model, not by your team. Check it before running.";
    static final int MAX_COMMAND_CHARS = 500;
    static final Duration COOL_DOWN = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(OllamaFixSuggester.class);

    private final HttpClient http;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper json;
    private final Clock clock;
    private final AtomicBoolean warned = new AtomicBoolean();
    private volatile Instant pausedUntil = Instant.MIN;

    public OllamaFixSuggester(String baseUrl, String model, Duration timeout, ObjectMapper json, Clock clock) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/generate");
        this.model = model;
        this.timeout = timeout;
        this.json = json;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Optional<Suggestion> suggest(SuggestionRequest request) {
        if (clock.instant().isBefore(pausedUntil)) {
            return Optional.empty();
        }
        try {
            String body = json.writeValueAsString(Map.of(
                    "model", model,
                    "prompt", prompt(request),
                    "stream", false,
                    "options", Map.of("temperature", 0.1, "num_predict", 96)));
            HttpRequest post = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(post, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return unavailable("HTTP " + response.statusCode() + " from " + endpoint);
            }
            JsonNode reply = json.readTree(response.body()).path("response");
            warned.set(false);
            return firstCommandLine(reply.asText("")).map(c -> new Suggestion(c, EXPLANATION));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            return unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Optional<Suggestion> unavailable(String why) {
        pausedUntil = clock.instant().plus(COOL_DOWN);
        if (warned.compareAndSet(false, true)) {
            log.warn("Ollama at {} is not usable ({}); answering without AI for now.", endpoint, why);
        }
        return Optional.empty();
    }

    static String prompt(SuggestionRequest r) {
        String where = "%s on %s".formatted(r.shell() == null ? "a shell" : r.shell(), r.os() == null ? "any OS" : r.os());
        String rules = "Reply with exactly one command line for " + where + ". Only the command: no explanation, "
                + "no markdown, no comments.";
        if (r.task() != null) {
            return "You are an expert at the command line. " + rules + "\nTask: " + r.task();
        }
        return "You are an expert at the command line. The command below failed. " + rules
                + " The command must fix the problem so the original command works when run again."
                + "\nCommand: " + r.commandText() + "\nError output:\n" + r.errorText();
    }

    /** The first line that looks like a command: code fences, prompts ($, >, PS>) and backticks removed. */
    static Optional<String> firstCommandLine(String reply) {
        for (String raw : reply.split("\\r?\\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("```")) {
                continue;
            }
            line = line.replaceFirst("^(?:PS>|PS [^>]*>|\\$|>)\\s*", "");
            if (line.length() >= 2 && line.startsWith("`") && line.endsWith("`")) {
                line = line.substring(1, line.length() - 1).strip();
            }
            if (!line.isEmpty()) {
                return Optional.of(line.length() > MAX_COMMAND_CHARS ? line.substring(0, MAX_COMMAND_CHARS) : line);
            }
        }
        return Optional.empty();
    }
}
