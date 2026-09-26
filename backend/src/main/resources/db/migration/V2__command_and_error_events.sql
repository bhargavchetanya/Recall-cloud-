CREATE TABLE command_events (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id      uuid        NOT NULL REFERENCES teams (id),
    user_id      uuid        NOT NULL REFERENCES users (id),
    session_id   text        NOT NULL,
    seq          bigint      NOT NULL,
    command_text text        NOT NULL,
    redacted     boolean     NOT NULL DEFAULT false,
    cwd          text,
    repo_name    text,
    shell        text,
    os           text,
    exit_code    integer,
    started_at   timestamptz NOT NULL,
    duration_ms  bigint,
    created_at   timestamptz NOT NULL DEFAULT now(),
    -- The extension retries batches; the same (user, terminal, seq) must never be stored twice.
    CONSTRAINT uq_command_events_user_session_seq UNIQUE (user_id, session_id, seq)
);

CREATE INDEX ix_command_events_team_created ON command_events (team_id, created_at);

-- normalized_error and fingerprint are filled in once fingerprinting exists (Phase 3).
CREATE TABLE error_events (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    command_event_id uuid        NOT NULL REFERENCES command_events (id) ON DELETE CASCADE,
    team_id          uuid        NOT NULL REFERENCES teams (id),
    raw_error        text        NOT NULL,
    normalized_error text,
    fingerprint      text,
    created_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_error_events_team_fingerprint ON error_events (team_id, fingerprint);
CREATE INDEX ix_error_events_team_created ON error_events (team_id, created_at);
CREATE INDEX ix_error_events_command_event ON error_events (command_event_id);
