-- One append-only record of what happened, across the whole app.
--
-- Nearly every table here holds the *current* state: a game is open, then full, then played or
-- called off, and only the last of those survives. That answers "how are things now", which is what
-- the app needs, and it cannot answer "how long did games take to fill", "what share of games that
-- filled were called off", "which venues turned bookings into played games" -- the questions a
-- business is run on.
--
-- Those answers cannot be reconstructed later. A row overwritten in March is gone in June, however
-- good the warehouse bought in July is. Recording events is the only part of an analytics strategy
-- that is urgent, because it is the only part that is irreversible. Everything downstream -- a
-- warehouse, a BI tool, a model -- can be added any time, and is easy precisely because this exists.
--
-- audit_events already had the right shape (an actor, a type, a subject, free-form details, a time);
-- it was simply pinned to a corporate workspace. Widening it keeps one event stream rather than
-- growing a second table that does the same job, and the corporate audit trail goes on reading its
-- own rows by organisation_id exactly as before.
ALTER TABLE audit_events ALTER COLUMN organisation_id DROP NOT NULL;
ALTER TABLE audit_events ALTER COLUMN actor_id DROP NOT NULL;

COMMENT ON TABLE audit_events IS
    'Append-only: what happened, who did it, and when. Never updated or deleted except with its subject.';
COMMENT ON COLUMN audit_events.organisation_id IS 'The workspace it belongs to, when it is one; null for everything outside corporate.';
COMMENT ON COLUMN audit_events.actor_id IS 'Who did it; null when the app itself did, such as a scheduled job.';
COMMENT ON COLUMN audit_events.details IS 'Facts about this event, as jsonb. Never personal detail that the subject rows do not already hold.';

-- Reading the stream by what happened, which is how every analytic question starts.
CREATE INDEX audit_events_by_type ON audit_events (event_type, occurred_at DESC);
-- And the history of one thing: a game, a venue, a competition.
CREATE INDEX audit_events_by_subject ON audit_events (subject_type, subject_id, occurred_at DESC)
    WHERE subject_id IS NOT NULL;
