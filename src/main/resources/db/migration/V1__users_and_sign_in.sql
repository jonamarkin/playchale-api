-- A player. Created the first time a phone number signs in, then filled in by onboarding.
CREATE TABLE users (
    id           uuid        PRIMARY KEY,
    -- E.164, e.g. +233241234567. How people sign in, so it's unique.
    phone        text        NOT NULL UNIQUE,
    -- ISO 3166-1 alpha-2 of the market they signed up in, e.g. GH. Money and times follow it.
    country      char(2)     NOT NULL,
    name         text        NOT NULL DEFAULT '',
    -- Public profile slug. Empty until onboarding picks one; unique ignoring case once set.
    handle       text        NOT NULL DEFAULT '',
    tint         text        NOT NULL,
    avatar_url   text,
    area         text,
    sports       text[]      NOT NULL DEFAULT '{}',
    position     text,
    -- Where money reaches them (E.164). Never used to charge them.
    payout_phone text,
    onboarded    boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX users_handle_unique ON users (lower(handle)) WHERE handle <> '';

-- A one-time sign-in code. Only a hash is stored, so a leaked database doesn't leak live codes.
CREATE TABLE sign_in_codes (
    id         uuid        PRIMARY KEY,
    phone      text        NOT NULL,
    code_hash  bytea       NOT NULL,
    -- Wrong guesses so far; the code stops working after a few.
    attempts   int         NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX sign_in_codes_by_phone ON sign_in_codes (phone, created_at DESC);

-- A signed-in device. The browser holds a random token; only its hash is stored here.
CREATE TABLE sessions (
    token_hash   bytea       PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX sessions_by_user ON sessions (user_id);
