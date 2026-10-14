-- A new venue is checked by PlayChale's staff before players can find or book it: anyone can list
-- one, and a fake venue taking bookings is the harm to stop. Waiting ('pending') until staff look;
-- 'approved' is live; 'declined' (with a note for the owner) is off until they change it, which
-- sends it back to waiting. Venues already listed were live, so they stay so.
ALTER TABLE venues
    ADD COLUMN review      text        NOT NULL DEFAULT 'approved' CHECK (review IN ('pending', 'approved', 'declined')),
    ADD COLUMN review_note text        CHECK (length(review_note) <= 280),
    ADD COLUMN reviewed_by uuid        REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN reviewed_at timestamptz;

-- From now on a new row waits, whoever writes it.
ALTER TABLE venues ALTER COLUMN review SET DEFAULT 'pending';

-- What the admin desk opens on: the venues waiting, oldest first.
CREATE INDEX venues_waiting ON venues (created_at) WHERE review = 'pending';
