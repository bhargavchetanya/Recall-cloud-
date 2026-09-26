-- gen_random_uuid() is built into PostgreSQL 13+.

CREATE TABLE teams (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        text        NOT NULL,
    invite_code text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_teams_invite_code UNIQUE (invite_code)
);

CREATE TABLE users (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    team_id       uuid        NOT NULL REFERENCES teams (id),
    email         text        NOT NULL,
    display_name  text        NOT NULL,
    password_hash text        NOT NULL,
    role          text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_role CHECK (role IN ('OWNER', 'MEMBER'))
);

-- Emails are compared case-insensitively.
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));
CREATE INDEX ix_users_team_id ON users (team_id);
