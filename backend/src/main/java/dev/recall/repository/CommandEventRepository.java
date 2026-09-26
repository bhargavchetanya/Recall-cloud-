package dev.recall.repository;

import dev.recall.domain.CapturedEvent;
import dev.recall.service.embedding.Vectors;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Plain JDBC: inserts need ON CONFLICT, and later phases add pgvector columns JPA does not map well. */
@Repository
public class CommandEventRepository {

    private final JdbcTemplate jdbc;

    public CommandEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the event, or returns empty if this (user, session, seq) was already stored.
     * {@code intentVector} is null for commands that are not searchable (failures, unknown exit code).
     */
    public Optional<UUID> insertIfAbsent(UUID teamId, UUID userId, CapturedEvent e, float[] intentVector) {
        List<UUID> ids = jdbc.query("""
                insert into command_events (team_id, user_id, session_id, seq, command_text, redacted, cwd, repo_name,
                                            shell, os, exit_code, started_at, duration_ms, intent_vector)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)
                on conflict (user_id, session_id, seq) do nothing
                returning id
                """,
                (rs, i) -> rs.getObject(1, UUID.class),
                teamId, userId, e.sessionId(), e.seq(), e.commandText(), e.redacted(), e.cwd(), e.repoName(),
                e.shell(), e.os(), e.exitCode(), OffsetDateTime.ofInstant(e.startedAt(), ZoneOffset.UTC), e.durationMs(),
                intentVector == null ? null : Vectors.toPgLiteral(intentVector));
        return ids.stream().findFirst();
    }

    /** True when this team already has a searchable (vector-carrying) row for exactly this command. */
    public boolean hasIntentVector(UUID teamId, String commandText) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from command_events
                               where team_id = ? and exit_code = 0 and md5(command_text) = md5(?)
                                 and intent_vector is not null)
                """, Boolean.class, teamId, commandText));
    }

    public void insertError(UUID commandEventId, UUID teamId, String rawError, String normalizedError,
                            String fingerprint, float[] errorVector) {
        jdbc.update("""
                insert into error_events (command_event_id, team_id, raw_error, normalized_error, fingerprint, error_vector)
                values (?, ?, ?, ?, ?, ?::vector)
                """, commandEventId, teamId, rawError, normalizedError, fingerprint,
                errorVector == null ? null : Vectors.toPgLiteral(errorVector));
    }
}
