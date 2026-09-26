-- Players can delete their account. The row stays, anonymised, so past games, results, league tables
-- and other people's statements still add up: the name becomes "Deleted player" and every personal
-- detail is removed. deleted_at says when.
ALTER TABLE users ADD COLUMN deleted_at timestamptz;

ALTER TABLE users DROP CONSTRAINT users_can_sign_in;
ALTER TABLE users ADD CONSTRAINT users_can_sign_in
    CHECK (phone IS NOT NULL OR sign_in_email IS NOT NULL OR deleted_at IS NOT NULL);
