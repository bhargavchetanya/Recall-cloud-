-- A command that made an error go away, for a team. One row per (error fingerprint, fix command).
CREATE TABLE fixes (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id           uuid        NOT NULL REFERENCES teams (id),
    fingerprint       text        NOT NULL,
    normalized_error  text        NOT NULL,
    fix_command       text        NOT NULL,
    source            text        NOT NULL,
    solved_by_user_id uuid        REFERENCES users (id),
    first_seen_at     timestamptz NOT NULL,
    last_used_at      timestamptz NOT NULL,
    worked_count      integer     NOT NULL DEFAULT 1,
    failed_count      integer     NOT NULL DEFAULT 0,
    error_vector      vector(384),
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_fixes_source CHECK (source IN ('LEARNED', 'AI', 'MANUAL')),
    CONSTRAINT uq_fixes_team_fingerprint_command UNIQUE (team_id, fingerprint, fix_command)
);

CREATE INDEX ix_fixes_team_fingerprint ON fixes (team_id, fingerprint);
CREATE INDEX ix_fixes_team_created ON fixes (team_id, created_at);
CREATE INDEX ix_fixes_error_hnsw ON fixes USING hnsw (error_vector vector_cosine_ops);

-- The learning loop's memory: the last failure in each terminal session that has not been fixed yet.
CREATE TABLE pending_errors (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id          uuid        NOT NULL REFERENCES teams (id),
    user_id          uuid        NOT NULL REFERENCES users (id),
    session_id       text        NOT NULL,
    seq              bigint      NOT NULL,
    command_text     text        NOT NULL,
    fingerprint      text        NOT NULL,
    normalized_error text        NOT NULL,
    error_vector     vector(384),
    failed_at        timestamptz NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_pending_errors_session UNIQUE (user_id, session_id)
);
