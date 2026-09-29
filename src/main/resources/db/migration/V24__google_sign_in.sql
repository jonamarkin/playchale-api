-- Signing in with Google: the account's Google ID (the token's "sub", which never changes), unique.
ALTER TABLE users ADD COLUMN google_sub text;
CREATE UNIQUE INDEX users_google_sub_unique ON users (google_sub) WHERE google_sub IS NOT NULL;

-- Google counts as a way in, so a Google account can drop its phone or email.
ALTER TABLE users DROP CONSTRAINT users_can_sign_in;
ALTER TABLE users ADD CONSTRAINT users_can_sign_in
    CHECK (phone IS NOT NULL OR sign_in_email IS NOT NULL OR google_sub IS NOT NULL OR deleted_at IS NOT NULL);
