-- Players telling PlayChale about something that shouldn't be there: a game, something said in
-- one, a person, a venue. Staff decide on the admin desk, all the reports about one thing at once:
-- take it down ('actioned') or leave it ('dismissed'). Whoever reported hears it was looked at.
CREATE TABLE reports (
    id           uuid        PRIMARY KEY,
    reporter_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    subject_type text        NOT NULL CHECK (subject_type IN ('game', 'message', 'user', 'venue')),
    subject_id   uuid        NOT NULL,
    reason       text        NOT NULL CHECK (reason IN ('spam', 'abuse', 'unsafe', 'wrong', 'other')),
    note         text        CHECK (length(note) <= 500),
    status       text        NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'actioned', 'dismissed')),
    -- What staff did or why they left it: for the desk, never shown to the reporter.
    outcome_note text        CHECK (length(outcome_note) <= 280),
    decided_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    decided_at   timestamptz,
    created_at   timestamptz NOT NULL
);

-- One open report per person per thing: tapping Report twice is the same report.
CREATE UNIQUE INDEX reports_open_once ON reports (reporter_id, subject_type, subject_id) WHERE status = 'open';
-- What the desk opens on.
CREATE INDEX reports_open ON reports (subject_type, subject_id) WHERE status = 'open';

-- The emails that tell staff something is waiting (a venue to check, a report), one row per email
-- round. The next goes out only for things that came in after the last, and never sooner than ten
-- minutes after it, so a burst of reports is one email, not a flood.
CREATE TABLE desk_alerts (
    sent_at timestamptz PRIMARY KEY,
    venues  integer     NOT NULL,
    reports integer     NOT NULL,
    sent_to integer     NOT NULL
);
