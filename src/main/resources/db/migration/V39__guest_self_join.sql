-- Joining a game without an account.
--
-- Until now a guest spot was always one the host held for someone (a friend not on PlayChale yet).
-- Now someone looking around can take a spot themselves, as a guest: a name, a phone number, and an
-- email if they like. The spot becomes theirs, with its result, when they sign in with that number
-- or address, or claim it from the browser they joined in.
ALTER TABLE game_participants ADD COLUMN guest_email text CHECK (length(guest_email) <= 254);
-- Whether the guest took the spot themselves, rather than the host holding it for them.
ALTER TABLE game_participants ADD COLUMN guest_self_joined boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN game_participants.guest_phone IS
    'A guest''s number (E.164): only shown to the host, and used to give them the spot when they sign in with it. Cleared 90 days after the game.';
COMMENT ON COLUMN game_participants.guest_email IS
    'A guest''s email (lower-case), if they gave one: as guest_phone.';

-- Signing in looks up the guest spots held under that number or address.
CREATE INDEX game_participants_guest_phone ON game_participants (guest_phone) WHERE user_id IS NULL AND guest_phone IS NOT NULL;
CREATE INDEX game_participants_guest_email ON game_participants (guest_email) WHERE user_id IS NULL AND guest_email IS NOT NULL;
