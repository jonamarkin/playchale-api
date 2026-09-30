CREATE TABLE competition_role_invitations (
    id              uuid        PRIMARY KEY,
    competition_id  uuid        NOT NULL REFERENCES competitions (id) ON DELETE CASCADE,
    team_id          uuid        REFERENCES teams (id) ON DELETE CASCADE,
    role             text        NOT NULL CHECK (role IN ('manager', 'team-manager')),
    token_hash       text        NOT NULL UNIQUE,
    invited_by       uuid        NOT NULL REFERENCES users (id),
    expires_at       timestamptz NOT NULL,
    accepted_by     uuid        REFERENCES users (id),
    accepted_at     timestamptz,
    created_at       timestamptz NOT NULL,
    CHECK ((role = 'team-manager') = (team_id IS NOT NULL)),
    CHECK ((accepted_by IS NULL) = (accepted_at IS NULL))
);
