-- Signing up to a games day through its link without an account: a name and a phone number, and
-- the games they want. Their phone keeps a token for the place (only its hash is here), to see their
-- games, change them or leave. Whoever later signs in with that number gets the place (the events
-- module's EventGuestClaims), and until then only the organisers see the number; it's erased 90
-- days after the event (EventGuestCleanup).
ALTER TABLE event_people DROP CONSTRAINT event_people_source_check;
ALTER TABLE event_people ADD CONSTRAINT event_people_source_check CHECK (source IN ('admin', 'link', 'rep', 'guest'));

ALTER TABLE event_people ADD COLUMN guest_phone text;
ALTER TABLE event_people ADD COLUMN guest_hash text UNIQUE;

COMMENT ON COLUMN event_people.guest_phone IS 'A guest sign-up''s number (E.164), unverified: organisers only, until claimed or 90 days after the event.';
COMMENT ON COLUMN event_people.guest_hash IS 'SHA-256 of the token the guest''s browser keeps for their place.';

-- One sign-up per number per event, and finding a number's places when it signs in.
CREATE UNIQUE INDEX event_people_guest_phone ON event_people (event_id, guest_phone) WHERE user_id IS NULL AND guest_phone IS NOT NULL;
CREATE INDEX event_people_by_guest_phone ON event_people (guest_phone) WHERE user_id IS NULL AND guest_phone IS NOT NULL;
