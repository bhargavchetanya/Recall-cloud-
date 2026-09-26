package dev.recall.service;

import dev.recall.service.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Checks the backend's runtime dependencies. The overall status is UP only when every component is UP. */
@Service
public class HealthService {

    public enum Status { UP, DOWN }

    public record Report(Status status, Status db, Status queue, Status embedder) {}

    static final String PROBE_TEXT = "health check";

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final JdbcTemplate jdbc;
    private final ConnectionFactory rabbit;
    private final EmbeddingService embeddings;

    public HealthService(JdbcTemplate jdbc, ConnectionFactory rabbit, EmbeddingService embeddings) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.embeddings = embeddings;
    }

    public Report check() {
        Status db = checkDatabase();
        Status queue = checkQueue();
        Status embedder = checkEmbedder();
        boolean allUp = db == Status.UP && queue == Status.UP && embedder == Status.UP;
        return new Report(allUp ? Status.UP : Status.DOWN, db, queue, embedder);
    }

    Status checkDatabase() {
        try {
            Integer one = jdbc.queryForObject("select 1", Integer.class);
            return Integer.valueOf(1).equals(one) ? Status.UP : Status.DOWN;
        } catch (RuntimeException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return Status.DOWN;
        }
    }

    Status checkQueue() {
        try (Connection connection = rabbit.createConnection()) {
            return connection.isOpen() ? Status.UP : Status.DOWN;
        } catch (RuntimeException e) {
            log.warn("Queue health check failed: {}", e.getMessage());
            return Status.DOWN;
        }
    }

    /** UP when the embedder returns a finite, non-zero vector of the right size. */
    Status checkEmbedder() {
        try {
            float[] v = embeddings.embed(PROBE_TEXT);
            boolean sane = v.length == EmbeddingService.DIMENSIONS;
            boolean nonZero = false;
            for (float x : v) {
                sane &= Float.isFinite(x);
                nonZero |= x != 0;
            }
            return sane && nonZero ? Status.UP : Status.DOWN;
        } catch (RuntimeException e) {
            log.warn("Embedder health check failed: {}", e.getMessage());
            return Status.DOWN;
        }
    }
}
