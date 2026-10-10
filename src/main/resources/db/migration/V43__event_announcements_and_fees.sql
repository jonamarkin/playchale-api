-- Announcements: what the organisers tell everyone on the day ("The football final moves to Court 2
-- at 3"). On the event's page and its board, and a notification for everyone in it with an account.
CREATE TABLE event_announcements (
    id        uuid        PRIMARY KEY,
    event_id  uuid        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    body      text        NOT NULL CHECK (length(body) BETWEEN 1 AND 500),
    posted_by uuid        REFERENCES users (id),
    posted_at timestamptz NOT NULL
);

CREATE INDEX event_announcements_by_event ON event_announcements (event_id, posted_at DESC);

-- An entry fee, tracked and never collected: people pay the organisers, who mark who has. In the
-- event's country's money, minor units; null for none.
ALTER TABLE events ADD COLUMN entry_fee bigint CHECK (entry_fee > 0);

ALTER TABLE event_people
    ADD COLUMN fee_paid_via  text CHECK (fee_paid_via IN ('cash', 'momo')),
    ADD COLUMN fee_paid_at   timestamptz,
    ADD COLUMN fee_marked_by uuid REFERENCES users (id);
