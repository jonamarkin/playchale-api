-- Players can sign in with a phone number or an email address, each proven by a one-time code.

-- A player signed up by email may have no phone number.
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;

-- The email a player signs in with: proven by a code, stored lower-case, never changed from the
-- profile page (so nobody can lock themselves out, or point someone else's address at their account).
-- users.email is separate: where receipts go, and freely editable.
ALTER TABLE users ADD COLUMN sign_in_email text;
CREATE UNIQUE INDEX users_sign_in_email_unique ON users (lower(sign_in_email)) WHERE sign_in_email IS NOT NULL;
ALTER TABLE users ADD CONSTRAINT users_can_sign_in CHECK (phone IS NOT NULL OR sign_in_email IS NOT NULL);

-- A code goes to a phone (SMS) or an email address.
ALTER TABLE sign_in_codes RENAME COLUMN phone TO recipient;
ALTER TABLE sign_in_codes ADD COLUMN channel text NOT NULL DEFAULT 'sms' CHECK (channel IN ('sms', 'email'));
ALTER TABLE sign_in_codes ALTER COLUMN channel DROP DEFAULT;
ALTER INDEX sign_in_codes_by_phone RENAME TO sign_in_codes_by_recipient;
