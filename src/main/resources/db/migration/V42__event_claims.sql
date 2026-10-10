-- Claim links: an admin sends someone they added by name a link of their own, so that person can
-- attach their account. From then on the games they played, and how they placed, count as theirs and
-- show on their profile. Only the link's hash is kept. Sending a new link replaces the old one; once
-- it's been claimed it only says whose it is.
ALTER TABLE event_people
    ADD COLUMN claim_hash      text,
    ADD COLUMN claim_issued_at timestamptz;

CREATE UNIQUE INDEX event_people_claim ON event_people (claim_hash) WHERE claim_hash IS NOT NULL;

COMMENT ON COLUMN event_people.claim_hash IS 'SHA-256 of the latest claim link''s token.';
