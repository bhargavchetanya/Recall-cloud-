package dev.recall.repository;

import dev.recall.service.embedding.Vectors;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stored fixes. "Demoted" fixes (at least {@code minFailures} failures and more failures than successes) are never
 * returned by the lookups.
 */
@Repository
public class FixRepository {

    /** A fix with who solved it; {@code cosine} is 1 for exact fingerprint matches. */
    public record Fix(UUID id, UUID teamId, String fixCommand, String solvedBy, Instant lastUsedAt, int workedCount,
                      int failedCount, double cosine) {}

    private static final String SELECT = """
            select f.id, f.team_id, f.fix_command, u.display_name, f.last_used_at, f.worked_count, f.failed_count
            """;
    private static final String NOT_DEMOTED = "not (f.failed_count >= ? and f.failed_count > f.worked_count)";

    private final JdbcTemplate jdbc;

    public FixRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The best fix for exactly this fingerprint: highest worked − 2·failed, then most recently used. */
    public Optional<Fix> bestExact(UUID teamId, String fingerprint, int minFailures) {
        return jdbc.query(SELECT + ", 1.0 as cosine" + """
                 from fixes f left join users u on u.id = f.solved_by_user_id
                where f.team_id = ? and f.fingerprint = ? and\s""" + NOT_DEMOTED + """
                 order by (f.worked_count - 2 * f.failed_count) desc, f.last_used_at desc
                limit 1
                """, FixRepository::map, teamId, fingerprint, minFailures).stream().findFirst();
    }

    /** The fix whose error is nearest to {@code errorVector}, whatever the distance; the caller applies a threshold. */
    @Transactional(readOnly = true)
    public Optional<Fix> nearest(UUID teamId, float[] errorVector, int minFailures) {
        jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        String v = Vectors.toPgLiteral(errorVector);
        return jdbc.query(SELECT + ", 1 - (f.error_vector <=> ?::vector) as cosine" + """
                 from fixes f left join users u on u.id = f.solved_by_user_id
                where f.team_id = ? and f.error_vector is not null and\s""" + NOT_DEMOTED + """
                 order by f.error_vector <=> ?::vector
                limit 1
                """, FixRepository::map, v, teamId, minFailures, v).stream().findFirst();
    }

    /**
     * Records that {@code fixCommand} fixed this error: a new row with worked_count 1, or +1 on an existing one.
     * The first solver stays the solver; last_used_at moves forward.
     */
    public UUID upsertLearned(UUID teamId, String fingerprint, String normalizedError, String fixCommand,
                              UUID solvedBy, Instant firstSeenAt, Instant usedAt, float[] errorVector) {
        return jdbc.queryForObject("""
                insert into fixes (team_id, fingerprint, normalized_error, fix_command, source, solved_by_user_id,
                                   first_seen_at, last_used_at, worked_count, error_vector)
                values (?, ?, ?, ?, 'LEARNED', ?, ?, ?, 1, ?::vector)
                on conflict (team_id, fingerprint, fix_command) do update
                   set worked_count = fixes.worked_count + 1,
                       last_used_at = greatest(fixes.last_used_at, excluded.last_used_at)
                returning id
                """, UUID.class, teamId, fingerprint, normalizedError, fixCommand, solvedBy, utc(firstSeenAt),
                utc(usedAt), errorVector == null ? null : Vectors.toPgLiteral(errorVector));
    }

    public Optional<UUID> teamOf(UUID fixId) {
        return jdbc.query("select team_id from fixes where id = ?", (rs, i) -> rs.getObject(1, UUID.class), fixId)
                .stream().findFirst();
    }

    public void recordFeedback(UUID fixId, boolean worked, Instant at) {
        if (worked) {
            jdbc.update("update fixes set worked_count = worked_count + 1, last_used_at = greatest(last_used_at, ?) "
                    + "where id = ?", utc(at), fixId);
        } else {
            jdbc.update("update fixes set failed_count = failed_count + 1 where id = ?", fixId);
        }
    }

    public List<Fix> forTeam(UUID teamId) {
        return jdbc.query(SELECT + ", 1.0 as cosine from fixes f left join users u on u.id = f.solved_by_user_id "
                + "where f.team_id = ? order by f.created_at", FixRepository::map, teamId);
    }

    private static Fix map(ResultSet rs, int i) throws SQLException {
        return new Fix(rs.getObject("id", UUID.class), rs.getObject("team_id", UUID.class),
                rs.getString("fix_command"), rs.getString("display_name"),
                rs.getTimestamp("last_used_at").toInstant(), rs.getInt("worked_count"), rs.getInt("failed_count"),
                rs.getDouble("cosine"));
    }

    private static OffsetDateTime utc(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
