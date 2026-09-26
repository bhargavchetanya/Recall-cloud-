CREATE EXTENSION IF NOT EXISTS vector;

-- Embedding of what a successful command is for (command text plus glossary phrases). Null for failures.
ALTER TABLE command_events ADD COLUMN intent_vector vector(384);
-- Filled in Phase 3 together with fingerprints.
ALTER TABLE error_events ADD COLUMN error_vector vector(384);

CREATE INDEX ix_command_events_intent_hnsw ON command_events USING hnsw (intent_vector vector_cosine_ops);
CREATE INDEX ix_error_events_error_hnsw ON error_events USING hnsw (error_vector vector_cosine_ops);

-- Usage statistics per distinct successful command; md5 because command_text can exceed a btree entry.
CREATE INDEX ix_command_events_team_command_md5 ON command_events (team_id, md5(command_text)) WHERE exit_code = 0;
