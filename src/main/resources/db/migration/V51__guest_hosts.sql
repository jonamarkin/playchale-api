-- Hosting a game without an account. A guest host is an account with no way of signing in but the
-- device it was made on (its session) and a manage key, under a name and a number they typed, which
-- nobody has checked. It hosts invite-only games, and nothing else that needs an account. It becomes
-- an ordinary account (the same row, so its games stay put) when someone signs in with that number,
-- or signs in on that device with a number, email or Google account nobody has yet.
ALTER TABLE users ADD COLUMN guest boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN guest_phone text;

ALTER TABLE users DROP CONSTRAINT users_can_sign_in;
ALTER TABLE users ADD CONSTRAINT users_can_sign_in
    CHECK (phone IS NOT NULL OR sign_in_email IS NOT NULL OR google_sub IS NOT NULL OR deleted_at IS NOT NULL OR guest);
-- A guest signs in nowhere: the moment it can, it isn't a guest any more.
ALTER TABLE users ADD CONSTRAINT users_guest_signs_in_nowhere
    CHECK (NOT guest OR (phone IS NULL AND sign_in_email IS NULL AND google_sub IS NULL));
-- One live guest per number: it's how the account finds its owner when they sign up.
CREATE UNIQUE INDEX users_live_guest_phone ON users (guest_phone) WHERE guest AND deleted_at IS NULL;

COMMENT ON COLUMN users.guest_phone IS 'A guest host''s number (E.164) as they typed it, unchecked: whoever signs in with it gets the account.';

-- A guest's session lets it do only what a guest host can (CurrentUserArgumentResolver).
ALTER TABLE sessions ADD COLUMN guest boolean NOT NULL DEFAULT false;

-- A guest host's way back on another browser: a private link, shown once (only its hash is here).
CREATE TABLE host_keys (
    user_id    uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    key_hash   text        NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    used_at    timestamptz
);
