-- The weekend nudge goes twice: Thursday evening in full (the email too, and a host with nothing on
-- asked to put a game up), then Friday evening a short reminder in the app for anyone still free.
-- One of each per person per weekend, whatever runs twice. Rows from before were all the one nudge.
ALTER TABLE weekend_nudges ADD COLUMN day text NOT NULL DEFAULT 'thu' CHECK (day IN ('thu', 'fri'));
ALTER TABLE weekend_nudges ALTER COLUMN day DROP DEFAULT;

ALTER TABLE weekend_nudges DROP CONSTRAINT weekend_nudges_pkey;
ALTER TABLE weekend_nudges ADD PRIMARY KEY (user_id, weekend, day);
