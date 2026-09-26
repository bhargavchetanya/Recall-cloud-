package dev.recall.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recall")
public record RecallProperties(Jwt jwt, Events events, Embedding embedding, Ranking ranking, Ai ai, Errors errors,
                               Learning learning, RateLimit rateLimit) {

    /** HMAC secret (at least 32 bytes) and token lifetime. */
    public record Jwt(String secret, Duration ttl) {}

    /**
     * Limits applied to captured terminal events. {@code instance} suffixes the queue and routing key; it is empty in
     * production and random per test context so contexts do not consume each other's messages.
     */
    public record Events(int maxBatchSize, int rawErrorMaxChars, Duration publishConfirmTimeout, String instance) {}

    /** Which embedder to use ({@code onnx} or {@code hashing}), where the ONNX files live, and the LRU cache size. */
    public record Embedding(String provider, String modelDir, int cacheSize) {}

    /** Weights and limits of the search ranking formula; see application.yml for the formula. */
    public record Ranking(double cosineWeight, double usageWeight, double recencyWeight, double contextWeight,
                          double usageSaturation, double recencyDays, double threshold, int annLimit,
                          int candidateLimit, int efSearch) {}

    /** Which fix suggester to use ({@code noop} or {@code ollama}) and how to reach Ollama. */
    public record Ai(String provider, String ollamaUrl, String model, Duration timeout) {}

    /** When a similar error's fix is good enough, and when bad feedback retires a fix. */
    public record Errors(double similarityThreshold, int demoteMinFailures) {}

    /** The learning loop's window and command lists; see application.yml. */
    public record Learning(Duration window, int maxCommands, List<String> unblockers, List<String> trivial) {}

    /** Requests per minute per user on POST /events. */
    public record RateLimit(int eventsPerMinute) {}
}
