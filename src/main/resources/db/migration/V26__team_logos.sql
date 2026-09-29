-- A team's crest: a company's logo, a school's badge. Kept in the database rather than a file store:
-- one small image per team (resized in the browser, and checked here), backed up with everything else.
ALTER TABLE teams ADD COLUMN logo bytea;
ALTER TABLE teams ADD COLUMN logo_type text;
-- Changes whenever the crest does, so a browser (and any CDN) fetches the new one.
ALTER TABLE teams ADD COLUMN logo_version timestamptz;

ALTER TABLE teams ADD CONSTRAINT teams_logo_whole
    CHECK ((logo IS NULL) = (logo_type IS NULL) AND (logo IS NULL) = (logo_version IS NULL));
