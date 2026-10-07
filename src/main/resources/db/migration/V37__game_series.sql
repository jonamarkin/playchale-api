-- Games that repeat: every week, every two weeks, or once a month on the same weekday ("the 2nd
-- Saturday", "the last Sunday"). A series holds what each of its games is set up from and when the
-- next one opens; every game it opens is an ordinary game with series_id set.
--
-- Only one is open at a time: the next opens when the last one ends (opens_at). So a series never
-- fills Discover with empty future games, or holds a pitch weeks before anyone has said they're in.
CREATE TABLE game_series (
    id               uuid        PRIMARY KEY,
    host_id          uuid        NOT NULL REFERENCES users (id),
    -- What each game is set up from: the same columns as games, changed only by the host.
    sport            text        NOT NULL,
    format           text        NOT NULL,
    title            text        NOT NULL,
    duration_minutes int         NOT NULL CHECK (duration_minutes BETWEEN 15 AND 480),
    venue_kind       text        NOT NULL CHECK (venue_kind IN ('listed', 'unlisted')),
    venue_id         uuid,
    pitch_id         uuid,
    venue_name       text        NOT NULL,
    venue_area       text,
    map_url          text,
    capacity         int         NOT NULL CHECK (capacity BETWEEN 2 AND 100),
    total_cost       bigint      NOT NULL CHECK (total_cost >= 0),
    pricing          text        NOT NULL CHECK (pricing IN ('split', 'per-player')),
    visibility       text        NOT NULL CHECK (visibility IN ('public', 'private')),
    notes            text,
    country          char(2)     NOT NULL,
    timezone         text        NOT NULL,
    -- When. The weekday and kick-off are local to timezone, so 6pm stays 6pm when the clocks change.
    frequency        text        NOT NULL CHECK (frequency IN ('weekly', 'fortnightly', 'monthly')),
    weekday          int         NOT NULL CHECK (weekday BETWEEN 1 AND 7),        -- ISO: 1 is Monday
    week_of_month    int         CHECK (week_of_month IN (-1, 1, 2, 3, 4)),       -- monthly only; -1 is the last
    kick_off         time        NOT NULL,
    -- Where it has got to: the game it will open next, and when (the end of the one before).
    next_starts_at   timestamptz NOT NULL,
    opens_at         timestamptz NOT NULL,
    last_game_id     uuid,
    -- Played games in a row that nobody but the host came to. Two, and it pauses rather than open a third.
    -- last_game_counted keeps a date that couldn't open (a pitch taken) from counting the same game twice.
    empty_runs       int         NOT NULL DEFAULT 0,
    last_game_counted boolean    NOT NULL DEFAULT false,
    status           text        NOT NULL CHECK (status IN ('active', 'paused', 'stopped')),
    paused_reason    text,
    stopped_at       timestamptz,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CHECK ((frequency = 'monthly') = (week_of_month IS NOT NULL)),
    CHECK ((venue_kind = 'listed') = (venue_id IS NOT NULL))
);

-- What the opener looks for every few minutes.
CREATE INDEX game_series_due ON game_series (opens_at) WHERE status = 'active';
CREATE INDEX game_series_host ON game_series (host_id);

ALTER TABLE games ADD COLUMN series_id uuid REFERENCES game_series (id);
-- However the opener is run, and however many copies of the API there are, a date opens once.
CREATE UNIQUE INDEX games_series_once ON games (series_id, starts_at) WHERE series_id IS NOT NULL;

ALTER TABLE game_series ADD CONSTRAINT game_series_last_game FOREIGN KEY (last_game_id) REFERENCES games (id) ON DELETE SET NULL;

-- Players who asked not to be invited to a series' games. They can still join any of them.
CREATE TABLE game_series_optouts (
    series_id uuid        NOT NULL REFERENCES game_series (id) ON DELETE CASCADE,
    user_id   uuid        NOT NULL REFERENCES users (id),
    at        timestamptz NOT NULL,
    PRIMARY KEY (series_id, user_id)
);
