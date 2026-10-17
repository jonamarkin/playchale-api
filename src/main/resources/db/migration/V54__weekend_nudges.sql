-- "This weekend near you": on Friday evening, open games near each person this weekend, as a
-- notification (and a push, unless they keep "Games near you" off their phone), and as an email for
-- those who agreed to hear from PlayChale and haven't switched this one off.

-- The email's own switch, inside "hear from PlayChale" like the Monday round-up. On within it.
ALTER TABLE email_preferences ADD COLUMN weekend boolean NOT NULL DEFAULT true;

-- The weekend mailout is its own kind of mailout. V30's check has no name of its own.
ALTER TABLE mailouts DROP CONSTRAINT mailouts_kind_check;
ALTER TABLE mailouts ADD CONSTRAINT mailouts_kind_check CHECK (kind IN ('announcement', 'digest', 'weekend'));

-- One nudge a weekend, whatever happens: a second run of the job (a restart, another copy of the
-- API) finds the row and sends nothing. 'games' listed open games; 'start' asked a host who has
-- nothing on to put one up.
CREATE TABLE weekend_nudges (
    user_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    weekend  date        NOT NULL,
    kind     text        NOT NULL CHECK (kind IN ('games', 'start')),
    games    integer     NOT NULL,
    sent_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, weekend)
);
