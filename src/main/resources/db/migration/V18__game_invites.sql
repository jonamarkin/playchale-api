-- Invites to a game, with each player's answer. Until now an invite was only a notification; now the
-- host sees who said yes, who said no and who hasn't answered. Nobody is put in a game (or owes a
-- share) without saying yes: accepting is joining, with the usual rules.
CREATE TABLE game_invites (
    game_id     uuid        NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id),
    -- Set when they were invited with their team, so the host sees which team answered how.
    team_id     uuid        REFERENCES teams (id) ON DELETE SET NULL,
    invited_by  uuid        NOT NULL REFERENCES users (id),
    status      text        NOT NULL CHECK (status IN ('pending', 'accepted', 'declined')),
    invited_at  timestamptz NOT NULL,
    answered_at timestamptz,
    PRIMARY KEY (game_id, user_id)
);

-- "Needs you": a player's invites still waiting for an answer.
CREATE INDEX game_invites_waiting ON game_invites (user_id) WHERE status = 'pending';
