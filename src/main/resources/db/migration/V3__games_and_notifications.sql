-- Games, who's in them, and in-app notifications.

-- A game someone hosts: at a partner venue (possibly on a booked pitch) or anywhere they type.
CREATE TABLE games (
    id               uuid        PRIMARY KEY,
    sport            text        NOT NULL,
    format           text        NOT NULL,
    title            text        NOT NULL,
    starts_at        timestamptz NOT NULL,
    duration_minutes int         NOT NULL CHECK (duration_minutes BETWEEN 15 AND 480),
    -- 'listed': a partner venue (venue_id, maybe pitch_id). 'unlisted': any name the host typed.
    -- Names are copied in, so a game still says where it was if the venue changes later.
    venue_kind       text        NOT NULL CHECK (venue_kind IN ('listed', 'unlisted')),
    venue_id         uuid,
    venue_name       text        NOT NULL,
    venue_area       text,
    pitch_id         uuid,
    pitch_name       text,
    capacity         int         NOT NULL CHECK (capacity BETWEEN 2 AND 100),
    -- The whole cost to share, in the game's currency, minor units. 0 is a free game.
    total_cost       bigint      NOT NULL CHECK (total_cost >= 0),
    currency         char(3)     NOT NULL,
    visibility       text        NOT NULL CHECK (visibility IN ('public', 'private')),
    host_id          uuid        NOT NULL REFERENCES users (id),
    notes            text,
    status           text        NOT NULL CHECK (status IN ('open', 'full', 'completed', 'cancelled')),
    cancelled_at     timestamptz,
    cancel_reason    text,
    -- Set when the game is a league fixture (the competitions module owns those ids).
    competition_id   uuid,
    fixture_round    int,
    home_team_id     uuid,
    away_team_id     uuid,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CHECK ((venue_kind = 'listed') = (venue_id IS NOT NULL))
);

-- Discover: upcoming games that are still on.
CREATE INDEX games_upcoming ON games (starts_at) WHERE status IN ('open', 'full');
CREATE INDEX games_by_host ON games (host_id, starts_at);

-- A spot in a game: a player, or a guest the host is holding it for.
CREATE TABLE game_participants (
    id              uuid        PRIMARY KEY,
    game_id         uuid        NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    -- The player. Null while the spot is held for a guest.
    user_id         uuid        REFERENCES users (id),
    joined_at       timestamptz NOT NULL,
    paid            boolean     NOT NULL DEFAULT false,
    paid_via        text        CHECK (paid_via IN ('app', 'cash')),
    payment_id      uuid,
    reminded_at     timestamptz,
    -- A guest spot: the name the host typed and, if given, their number (E.164), only ever used
    -- to match whoever claims it.
    guest_name      text,
    guest_phone     text,
    -- SHA-256 of the claim link's token. The token itself is only ever shown to the host, once.
    guest_claim_hash text       UNIQUE,
    guest_added_by  uuid        REFERENCES users (id),
    CHECK ((user_id IS NULL) = (guest_name IS NOT NULL)),
    UNIQUE (game_id, user_id)
);

CREATE INDEX game_participants_by_user ON game_participants (user_id) WHERE user_id IS NOT NULL;

-- Something that happened that a player should know about, shown in the app's notification list.
CREATE TABLE notifications (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       text        NOT NULL,
    title      text        NOT NULL,
    body       text        NOT NULL,
    -- In-app path to open, e.g. /games/<id>?pay=1.
    link       text,
    -- Who caused it, if anyone.
    actor_id   uuid        REFERENCES users (id),
    created_at timestamptz NOT NULL,
    read_at    timestamptz
);

CREATE INDEX notifications_by_user ON notifications (user_id, created_at DESC);
