package dev.recall.repository;

import dev.recall.service.embedding.Vectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Vector search over a team's successful commands, plus usage statistics per distinct command. */
@Repository
public class CommandSearchRepository {

    /** A distinct command near the query, with its best cosine similarity. */
    public record Neighbor(String commandText, double cosine) {}

    /** How a command has been used across the team's whole history. */
    public record Usage(String commandText, long uses, java.time.Instant lastUsedAt, String lastUsedBy,
                        boolean contextMatch) {}

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public CommandSearchRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    /**
     * Up to {@code distinctLimit} distinct commands closest to {@code query}, nearest first, taken from the
     * {@code annLimit} nearest rows of the HNSW index. Iterative index scans keep the team filter from starving the
     * result, and must be set in the same transaction as the query.
     */
    @Transactional(readOnly = true)
    public List<Neighbor> nearestCommands(UUID teamId, float[] query, int annLimit, int distinctLimit, int efSearch) {
        jdbc.execute("SET LOCAL hnsw.ef_search = " + Math.max(annLimit, efSearch));
        jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
        String vector = Vectors.toPgLiteral(query);
        List<Neighbor> rows = jdbc.query("""
                select command_text, 1 - (intent_vector <=> ?::vector) as cosine
                from command_events
                where team_id = ? and exit_code = 0 and intent_vector is not null
                order by intent_vector <=> ?::vector
                limit ?
                """,
                (rs, i) -> new Neighbor(rs.getString(1), rs.getDouble(2)),
                vector, teamId, vector, annLimit);

        Map<String, Neighbor> distinct = new LinkedHashMap<>();
        for (Neighbor n : rows) {
            distinct.merge(n.commandText(), n, (a, b) -> a.cosine() >= b.cosine() ? a : b);
        }
        List<Neighbor> result = new ArrayList<>(distinct.values());
        result.sort((a, b) -> Double.compare(b.cosine(), a.cosine()));
        return result.subList(0, Math.min(distinctLimit, result.size()));
    }

    /** Uses, last use, last user and whether any use was in the given repo or cwd, for each command given. */
    public Map<String, Usage> usage(UUID teamId, List<String> commands, String repoName, String cwd) {
        if (commands.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("team", teamId)
                .addValue("hashes", commands.stream().map(CommandSearchRepository::md5).toList())
                .addValue("repo", repoName)
                .addValue("cwd", cwd);
        Map<String, Usage> out = new LinkedHashMap<>();
        named.query("""
                select c.command_text,
                       count(*) as uses,
                       max(c.started_at) as last_used_at,
                       (array_agg(u.display_name order by c.started_at desc))[1] as last_user,
                       bool_or((cast(:repo as text) is not null and c.repo_name = cast(:repo as text))
                            or (cast(:cwd as text) is not null and c.cwd = cast(:cwd as text))) as context_match
                from command_events c
                join users u on u.id = c.user_id
                where c.team_id = :team and c.exit_code = 0 and md5(c.command_text) in (:hashes)
                group by c.command_text
                """, params, rs -> {
                    String text = rs.getString("command_text");
                    Timestamp last = rs.getTimestamp("last_used_at");
                    out.put(text, new Usage(text, rs.getLong("uses"), last.toInstant(), rs.getString("last_user"),
                            rs.getBoolean("context_match")));
                });
        return out;
    }

    static String md5(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
