package dev.recall.repository;

import dev.recall.service.embedding.Vectors;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The last unfixed failure per terminal session. Kept in the database so ingestion can run on any consumer. */
@Repository
public class PendingErrorRepository {

    public record Pending(UUID teamId, UUID userId, String sessionId, long seq, String commandText, String fingerprint,
                          String normalizedError, float[] errorVector, Instant failedAt) {}

    private final JdbcTemplate jdbc;

    public PendingErrorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Pending> find(UUID userId, String sessionId) {
        return jdbc.query("""
                select team_id, user_id, session_id, seq, command_text, fingerprint, normalized_error,
                       error_vector::text as v, failed_at
                from pending_errors where user_id = ? and session_id = ?
                """, (rs, i) -> new Pending(rs.getObject("team_id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("session_id"), rs.getLong("seq"), rs.getString("command_text"),
                        rs.getString("fingerprint"), rs.getString("normalized_error"), parse(rs.getString("v")),
                        rs.getTimestamp("failed_at").toInstant()),
                userId, sessionId).stream().findFirst();
    }

    /** Opens the pending error for this session, replacing an older one. */
    public void save(Pending p) {
        jdbc.update("""
                insert into pending_errors (team_id, user_id, session_id, seq, command_text, fingerprint,
                                            normalized_error, error_vector, failed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?::vector, ?)
                on conflict (user_id, session_id) do update
                   set seq = excluded.seq, command_text = excluded.command_text, fingerprint = excluded.fingerprint,
                       normalized_error = excluded.normalized_error, error_vector = excluded.error_vector,
                       failed_at = excluded.failed_at, team_id = excluded.team_id
                """, p.teamId(), p.userId(), p.sessionId(), p.seq(), p.commandText(), p.fingerprint(),
                p.normalizedError(), p.errorVector() == null ? null : Vectors.toPgLiteral(p.errorVector()),
                OffsetDateTime.ofInstant(p.failedAt(), ZoneOffset.UTC));
    }

    public void delete(UUID userId, String sessionId) {
        jdbc.update("delete from pending_errors where user_id = ? and session_id = ?", userId, sessionId);
    }

    static float[] parse(String literal) {
        if (literal == null) {
            return null;
        }
        String body = literal.substring(1, literal.length() - 1);
        if (body.isBlank()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i]);
        }
        return v;
    }
}
