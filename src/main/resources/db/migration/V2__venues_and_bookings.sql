-- Partner venues, their pitches, and bookings of those pitches.

-- Lets one exclusion constraint compare a uuid (=) and a time range (&&) together; see bookings.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- A place players can book in the app, run by an owner (a PlayChale user).
CREATE TABLE venues (
    id          uuid        PRIMARY KEY,
    owner_id    uuid        NOT NULL REFERENCES users (id),
    name        text        NOT NULL,
    area        text        NOT NULL,
    description text,
    -- Directions or a GhanaPost GPS address.
    address     text,
    -- Contact number (E.164), shown to players who book.
    phone       text,
    -- The market it's in: money is in its currency, opening hours are in its timezone.
    country     char(2)     NOT NULL,
    currency    char(3)     NOT NULL,
    timezone    text        NOT NULL,
    -- Listed venues can be found and booked in the app.
    listed      boolean     NOT NULL DEFAULT true,
    -- Opening hours by weekday, Sunday first: 'HH:MM-HH:MM' in local time, or '' when closed.
    hours       text[]      NOT NULL CHECK (cardinality(hours) = 7),
    amenities   text[]      NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL,
    updated_at  timestamptz NOT NULL
);

CREATE INDEX venues_by_owner ON venues (owner_id);

-- One bookable pitch or court.
CREATE TABLE pitches (
    id             uuid        PRIMARY KEY,
    venue_id       uuid        NOT NULL REFERENCES venues (id),
    name           text        NOT NULL,
    sport          text        NOT NULL,
    -- The format it's sized for, e.g. 5-a-side.
    format         text        NOT NULL,
    surface        text        NOT NULL CHECK (surface IN ('turf', 'grass', 'hard', 'sand', 'indoor')),
    -- In the venue's currency, minor units (pesewas).
    price_per_hour bigint      NOT NULL CHECK (price_per_hour > 0),
    -- Where it's listed on the venue page.
    position       int         NOT NULL,
    -- Set when the owner removes it. Kept, not deleted, so past bookings still say where they were.
    removed_at     timestamptz
);

CREATE INDEX pitches_by_venue ON pitches (venue_id);

-- A pitch held for a time: by a game, or blocked by the owner (walk-ins, maintenance, cash regulars).
CREATE TABLE bookings (
    id         uuid        PRIMARY KEY,
    venue_id   uuid        NOT NULL REFERENCES venues (id),
    pitch_id   uuid        NOT NULL REFERENCES pitches (id),
    starts_at  timestamptz NOT NULL,
    ends_at    timestamptz NOT NULL CHECK (ends_at > starts_at),
    kind       text        NOT NULL CHECK (kind IN ('game', 'block')),
    -- The game that holds it (game bookings only). The games module owns games, so no foreign key.
    game_id    uuid,
    -- The game's host, or the owner for blocks.
    booked_by  uuid        NOT NULL REFERENCES users (id),
    -- What the slot costs, in the venue's currency (0 for blocks).
    price      bigint      NOT NULL DEFAULT 0,
    note       text,
    status     text        NOT NULL CHECK (status IN ('confirmed', 'cancelled')),
    created_at timestamptz NOT NULL,
    CHECK ((kind = 'game') = (game_id IS NOT NULL)),
    -- No two confirmed bookings of one pitch overlap, however many requests race for it. The
    -- default range is [start, end), so back-to-back bookings are fine.
    CONSTRAINT bookings_no_overlap EXCLUDE USING gist (pitch_id WITH =, tstzrange(starts_at, ends_at) WITH &&)
        WHERE (status = 'confirmed')
);

CREATE INDEX bookings_by_venue_and_time ON bookings (venue_id, starts_at);
CREATE INDEX bookings_by_game ON bookings (game_id) WHERE game_id IS NOT NULL;
