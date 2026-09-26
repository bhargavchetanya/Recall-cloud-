package dev.recall.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.recall.ManualClock;
import dev.recall.service.ai.FixSuggester.Suggestion;
import dev.recall.service.ai.FixSuggester.SuggestionRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Against a fake Ollama on a local port: what is sent, what is extracted, and every way it can fail. */
class OllamaFixSuggesterTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ManualClock clock = new ManualClock();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> reply = new AtomicReference<>("{\"response\":\"ls\"}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger delayMs = new AtomicInteger(0);
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsModelAndTaskPromptAndReturnsTheCommand() throws IOException {
        reply.set("{\"response\":\"```bash\\ndu -sh * | sort -h\\n```\"}");

        Optional<Suggestion> s = suggester(start(), Duration.ofSeconds(5))
                .suggest(SuggestionRequest.forTask("which folders are biggest", "bash", "linux", null, null));

        assertThat(s).contains(new Suggestion("du -sh * | sort -h", OllamaFixSuggester.EXPLANATION));
        JsonNode sent = requests.get(0);
        assertThat(sent.get("model").asText()).isEqualTo("qwen2.5-coder:7b");
        assertThat(sent.get("stream").asBoolean()).isFalse();
        assertThat(sent.get("prompt").asText()).contains("Task: which folders are biggest", "bash on linux",
                "exactly one command line");
    }

    @Test
    void errorPromptCarriesTheCommandAndTheErrorOutput() throws IOException {
        suggester(start(), Duration.ofSeconds(5)).suggest(new SuggestionRequest(null,
                "ModuleNotFoundError: No module named 'requests'", "python app.py", null, null, null, null));

        assertThat(requests.get(0).get("prompt").asText())
                .contains("The command below failed", "Command: python app.py",
                        "Error output:\nModuleNotFoundError: No module named 'requests'", "a shell on any OS");
    }

    @Test
    void unreachableOllamaIsEmptyAndPausesFurtherCalls() throws IOException {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        OllamaFixSuggester dead = new OllamaFixSuggester("http://127.0.0.1:" + deadPort, "m", Duration.ofSeconds(2),
                json, clock);

        long start = System.nanoTime();
        assertThat(dead.suggest(task())).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void afterAFailureItWaitsOneMinuteBeforeTryingAgain() throws IOException {
        int port = start();
        status.set(500);
        OllamaFixSuggester ollama = suggester(port, Duration.ofSeconds(5));

        assertThat(ollama.suggest(task())).isEmpty();
        status.set(200);
        assertThat(ollama.suggest(task())).as("still cooling down").isEmpty();
        assertThat(requests).hasSize(1);

        clock.now = clock.now.plus(OllamaFixSuggester.COOL_DOWN);
        assertThat(ollama.suggest(task())).contains(new Suggestion("ls", OllamaFixSuggester.EXPLANATION));
    }

    @Test
    void slowOllamaTimesOut() throws IOException {
        delayMs.set(2_000);
        long start = System.nanoTime();
        assertThat(suggester(start(), Duration.ofMillis(300)).suggest(task())).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
    }

    @Test
    void emptyOrMalformedRepliesAreEmpty() throws IOException {
        int port = start();
        reply.set("{\"response\":\"  \\n```\\n```\"}");
        assertThat(suggester(port, Duration.ofSeconds(5)).suggest(task())).isEmpty();
        reply.set("not json");
        assertThat(suggester(port, Duration.ofSeconds(5)).suggest(task())).isEmpty();
    }

    @Test
    void firstCommandLineStripsPromptsFencesAndBackticks() {
        assertThat(OllamaFixSuggester.firstCommandLine("$ npm ci")).contains("npm ci");
        assertThat(OllamaFixSuggester.firstCommandLine("PS C:\\app> Get-Process node")).contains("Get-Process node");
        assertThat(OllamaFixSuggester.firstCommandLine("`git stash`")).contains("git stash");
        assertThat(OllamaFixSuggester.firstCommandLine("```sh\n> docker ps -a\n```\nThis lists all.")).contains("docker ps -a");
        assertThat(OllamaFixSuggester.firstCommandLine("echo " + "x".repeat(600)).orElseThrow())
                .hasSize(OllamaFixSuggester.MAX_COMMAND_CHARS);
        assertThat(OllamaFixSuggester.firstCommandLine("")).isEmpty();
    }

    private static SuggestionRequest task() {
        return SuggestionRequest.forTask("list files", null, null, null, null);
    }

    private OllamaFixSuggester suggester(int port, Duration timeout) {
        return new OllamaFixSuggester("http://127.0.0.1:" + port + "/", "qwen2.5-coder:7b", timeout, json, clock);
    }

    private int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server.getAddress().getPort();
    }
}
