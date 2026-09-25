-- Competitions: leagues of teams, whose fixtures are ordinary games (games.competition_id).
-- "Competition" rather than "league" so the same thing fits corporate leagues, inter-school events
-- and community tournaments.

CREATE TABLE competitions (
    id               uuid        PRIMARY KEY,
    name             text        NOT NULL,
    sport            text        NOT NULL,
    format           text        NOT NULL,
    organiser_id     uuid        NOT NULL REFERENCES users (id),
    -- Where fixtures are played, copied into each fixture: a partner venue or any name.
    venue_kind       text        NOT NULL CHECK (venue_kind IN ('listed', 'unlisted')),
    venue_id         uuid,
    venue_name       text        NOT NULL,
    venue_area       text,
    -- Kick-off of the first matchday; later rounds fall a week apart.
    starts_at        timestamptz NOT NULL,
    duration_minutes int         NOT NULL CHECK (duration_minutes BETWEEN 15 AND 480),
    status           text        NOT NULL CHECK (status IN ('draft', 'running', 'finished')),
    -- Kept per competition, because not every league gives three points for a win.
    points_win       int         NOT NULL DEFAULT 3,
    points_draw      int         NOT NULL DEFAULT 1,
    points_loss      int         NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CHECK ((venue_kind = 'listed') = (venue_id IS NOT NULL))
);

CREATE INDEX competitions_by_organiser ON competitions (organiser_id);

CREATE TABLE teams (
    id             uuid        PRIMARY KEY,
    competition_id uuid        NOT NULL REFERENCES competitions (id) ON DELETE CASCADE,
    name           text        NOT NULL,
    captain_id     uuid        NOT NULL REFERENCES users (id),
    -- Background of the crest, so teams are told apart at a glance.
    tint           text        NOT NULL,
    -- Goes in the squad link the captain shares. Only the captain and the organiser ever see it.
    join_token     text        NOT NULL UNIQUE,
    created_at     timestamptz NOT NULL
);

-- Team names are unique within a league, ignoring case.
CREATE UNIQUE INDEX teams_name_unique ON teams (competition_id, lower(name));

-- Who is in each squad.
CREATE TABLE team_players (
    team_id        uuid        NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    -- Repeated from the team so the database can hold a player to one team per competition.
    competition_id uuid        NOT NULL REFERENCES competitions (id) ON DELETE CASCADE,
    user_id        uuid        NOT NULL REFERENCES users (id),
    added_at       timestamptz NOT NULL,
    PRIMARY KEY (team_id, user_id),
    UNIQUE (competition_id, user_id)
);

CREATE INDEX team_players_by_user ON team_players (user_id);

-- Someone asking a captain for a place in their squad.
CREATE TABLE join_requests (
    id             uuid        PRIMARY KEY,
    competition_id uuid        NOT NULL REFERENCES competitions (id) ON DELETE CASCADE,
    team_id        uuid        NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    user_id        uuid        NOT NULL REFERENCES users (id),
    status         text        NOT NULL CHECK (status IN ('pending', 'accepted', 'declined')),
    created_at     timestamptz NOT NULL,
    answered_at    timestamptz
);

-- One request at a time per player per team.
CREATE UNIQUE INDEX join_requests_one_pending ON join_requests (team_id, user_id) WHERE status = 'pending';
