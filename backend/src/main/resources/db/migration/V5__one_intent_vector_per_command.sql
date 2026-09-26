-- HNSW search degrades badly when the index holds many identical vectors, and team history repeats the same
-- commands constantly. From now on only the first successful use of a command in a team carries its intent vector;
-- later uses are still counted through ix_command_events_team_command_md5. Clear the vectors of existing repeats.
UPDATE command_events c
   SET intent_vector = NULL
 WHERE c.intent_vector IS NOT NULL
   AND EXISTS (SELECT 1
                 FROM command_events d
                WHERE d.team_id = c.team_id
                  AND d.exit_code = 0
                  AND md5(d.command_text) = md5(c.command_text)
                  AND d.intent_vector IS NOT NULL
                  AND (d.created_at, d.id) < (c.created_at, c.id));
