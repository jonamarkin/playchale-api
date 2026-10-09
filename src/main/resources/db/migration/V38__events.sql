-- Events: a church games day, a school's inter-house sports, a fellowship's funday.
--
-- One event holds many games at once (football and volleyball, but also table tennis, oware, ludo,
-- draughts, races and quizzes), the people taking part, and the groups they compete for
-- (fellowships, houses, classes). Every game is won by a person, a pair or a team, and the places
-- in every game add up to one overall table of groups.
--
-- It lives in an organisation workspace, so the same owners, admins and officials run it. It does
-- not reuse competitions: a competition is one sport between teams of account holders, played as
-- PlayChale games; most people at a games day have no account and nobody books a pitch for ludo.

CREATE TABLE events (
    id                uuid        PRIMARY KEY,
    organisation_id   uuid        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    name              text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    starts_on         date        NOT NULL,
    ends_on           date        NOT NULL,
    timezone          text        NOT NULL,
    country           char(2)     NOT NULL,
    venue_name        text        CHECK (length(venue_name) <= 120),
    venue_area        text        CHECK (length(venue_area) <= 120),
    map_url           text        CHECK (length(map_url) <= 500),
    status            text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'finished', 'cancelled')),
    -- Whether the join link still takes people. Admins can always add names themselves.
    registration_open boolean     NOT NULL DEFAULT true,
    -- The join link and the projector board's link. Kept readable so an admin can copy them again,
    -- and changed (not revealed) when one has gone too far.
    join_code         text        NOT NULL UNIQUE,
    board_token       text        NOT NULL UNIQUE,
    -- What a 1st, 2nd, 3rd... place in any game is worth to a group in the overall table.
    placing_points    integer[]   NOT NULL DEFAULT '{5,3,1}',
    created_by        uuid        REFERENCES users (id),
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT events_dates CHECK (ends_on >= starts_on),
    CONSTRAINT events_points CHECK (cardinality(placing_points) BETWEEN 1 AND 8)
);

CREATE INDEX events_by_organisation ON events (organisation_id, starts_on DESC);

-- Fellowships, houses, classes: who the people compete for.
CREATE TABLE event_groups (
    id         uuid        PRIMARY KEY,
    event_id   uuid        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 40),
    colour     text        NOT NULL CHECK (colour ~ '^#[0-9a-fA-F]{6}$'),
    position   integer     NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX event_groups_name ON event_groups (event_id, lower(name));

-- Everyone taking part. Most are names an admin typed in; some joined with their own account.
CREATE TABLE event_people (
    id           uuid        PRIMARY KEY,
    event_id     uuid        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    display_name text        NOT NULL CHECK (length(display_name) BETWEEN 1 AND 80),
    user_id      uuid        REFERENCES users (id),
    group_id     uuid        REFERENCES event_groups (id) ON DELETE SET NULL,
    source       text        NOT NULL CHECK (source IN ('admin', 'link')),
    added_by     uuid        REFERENCES users (id),
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL
);

CREATE INDEX event_people_by_event ON event_people (event_id, lower(display_name));
CREATE UNIQUE INDEX event_people_account ON event_people (event_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX event_people_by_user ON event_people (user_id) WHERE user_id IS NOT NULL;

-- The games in the event: table tennis (men), sack race (under 10), oware...
CREATE TABLE event_games (
    id               uuid        PRIMARY KEY,
    event_id         uuid        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    -- A key from the ready-made list ("table-tennis", "oware"), or "custom".
    discipline       text        NOT NULL CHECK (discipline ~ '^[a-z][a-z-]{1,30}$'),
    name             text        NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
    category         text        CHECK (length(category) BETWEEN 1 AND 40),
    entry_kind       text        NOT NULL CHECK (entry_kind IN ('single', 'pair', 'team')),
    team_size        smallint    CHECK (team_size BETWEEN 2 AND 60),
    format           text        NOT NULL CHECK (format IN ('knockout', 'league', 'placings')),
    -- score: goals or points; sets: best of 1, 3 or 5; outcome: win, draw or lose (oware, chess);
    -- placings: finishing order (ludo, races).
    scoring          text        NOT NULL CHECK (scoring IN ('score', 'sets', 'outcome', 'placings')),
    best_of          smallint    CHECK (best_of IN (1, 3, 5)),
    draws_allowed    boolean     NOT NULL DEFAULT false,
    third_place      boolean     NOT NULL DEFAULT false,
    heat_size        smallint    CHECK (heat_size BETWEEN 2 AND 20),
    advance_per_heat smallint    CHECK (advance_per_heat BETWEEN 1 AND 10),
    location         text        CHECK (length(location) BETWEEN 1 AND 60),
    starts_at        timestamptz,
    status           text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'drawn', 'finished')),
    position         integer     NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT event_games_placings CHECK ((format = 'placings') = (scoring = 'placings')),
    CONSTRAINT event_games_sets CHECK (scoring <> 'sets' OR best_of IS NOT NULL),
    CONSTRAINT event_games_heats CHECK (format <> 'placings' OR (heat_size IS NOT NULL AND advance_per_heat IS NOT NULL))
);

CREATE INDEX event_games_by_event ON event_games (event_id, position);

-- Who runs a game on the day: its draw, its entries and its results, and nothing else.
CREATE TABLE event_game_coordinators (
    game_id    uuid        NOT NULL REFERENCES event_games (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id),
    added_by   uuid        REFERENCES users (id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (game_id, user_id)
);

CREATE INDEX event_game_coordinators_by_user ON event_game_coordinators (user_id);

-- Someone who wants to play a pair or team game and isn't in a pair or team yet.
CREATE TABLE event_game_interest (
    game_id    uuid        NOT NULL REFERENCES event_games (id) ON DELETE CASCADE,
    person_id  uuid        NOT NULL REFERENCES event_people (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (game_id, person_id)
);

-- Who plays in a game: one person, a pair, or a team.
CREATE TABLE event_entries (
    id         uuid        PRIMARY KEY,
    game_id    uuid        NOT NULL REFERENCES event_games (id) ON DELETE CASCADE,
    name       text        NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    group_id   uuid        REFERENCES event_groups (id) ON DELETE SET NULL,
    -- The order entries came in, which is the seeding the draw uses.
    seed       integer     NOT NULL,
    status     text        NOT NULL DEFAULT 'entered' CHECK (status IN ('entered', 'withdrawn')),
    created_at timestamptz NOT NULL
);

CREATE INDEX event_entries_by_game ON event_entries (game_id, seed);

CREATE TABLE event_entry_people (
    entry_id  uuid NOT NULL REFERENCES event_entries (id) ON DELETE CASCADE,
    person_id uuid NOT NULL REFERENCES event_people (id) ON DELETE CASCADE,
    -- Repeated from the entry so the database itself keeps a person to one entry per game.
    game_id   uuid NOT NULL REFERENCES event_games (id) ON DELETE CASCADE,
    PRIMARY KEY (entry_id, person_id),
    UNIQUE (game_id, person_id)
);

-- Two-sided games: a knockout tie or a league match. An empty side is a bye, or a winner still
-- to come through.
CREATE TABLE event_matches (
    id              uuid        PRIMARY KEY,
    game_id         uuid        NOT NULL REFERENCES event_games (id) ON DELETE CASCADE,
    round           smallint    NOT NULL CHECK (round >= 1),
    slot            smallint    NOT NULL CHECK (slot >= 0),
    -- The match for third place, played alongside the final.
    third_place     boolean     NOT NULL DEFAULT false,
    home_entry_id   uuid        REFERENCES event_entries (id) ON DELETE SET NULL,
    away_entry_id   uuid        REFERENCES event_entries (id) ON DELETE SET NULL,
    home_score      integer     CHECK (home_score >= 0),
    away_score      integer     CHECK (away_score >= 0),
    winner_entry_id uuid        REFERENCES event_entries (id) ON DELETE SET NULL,
    -- How it was settled: on the score, on penalties, or one side didn't turn up.
    decided_by      text        CHECK (decided_by IN ('score', 'penalties', 'walkover')),
    home_penalties  smallint    CHECK (home_penalties >= 0),
    away_penalties  smallint    CHECK (away_penalties >= 0),
    starts_at       timestamptz,
    location        text        CHECK (length(location) BETWEEN 1 AND 60),
    recorded_by     uuid        REFERENCES users (id),
    recorded_at     timestamptz,
    UNIQUE (game_id, round, slot, third_place)
);

CREATE INDEX event_matches_recorded ON event_matches (game_id, recorded_at DESC) WHERE recorded_at IS NOT NULL;

CREATE TABLE event_match_sets (
    match_id uuid     NOT NULL REFERENCES event_matches (id) ON DELETE CASCADE,
    set_no   smallint NOT NULL CHECK (set_no BETWEEN 1 AND 5),
    home     smallint NOT NULL CHECK (home >= 0),
    away     smallint NOT NULL CHECK (away >= 0),
    PRIMARY KEY (match_id, set_no)
);

-- Placings games: ludo boards, race heats, and the final the best of them go through to.
CREATE TABLE event_heats (
    id          uuid        PRIMARY KEY,
    game_id     uuid        NOT NULL REFERENCES event_games (id) ON DELETE CASCADE,
    stage       text        NOT NULL CHECK (stage IN ('heat', 'final')),
    number      smallint    NOT NULL CHECK (number >= 1),
    starts_at   timestamptz,
    location    text        CHECK (length(location) BETWEEN 1 AND 60),
    recorded_by uuid        REFERENCES users (id),
    recorded_at timestamptz,
    UNIQUE (game_id, stage, number)
);

CREATE TABLE event_heat_entries (
    heat_id  uuid     NOT NULL REFERENCES event_heats (id) ON DELETE CASCADE,
    entry_id uuid     NOT NULL REFERENCES event_entries (id) ON DELETE CASCADE,
    lane     smallint NOT NULL CHECK (lane >= 1),
    place    smallint CHECK (place >= 1),
    -- A time or a distance, as the coordinator wrote it ("12.4 s"). Shown, never compared.
    mark     text     CHECK (length(mark) BETWEEN 1 AND 20),
    PRIMARY KEY (heat_id, entry_id),
    UNIQUE (heat_id, lane)
);
