-- An event's public page, when its admins turn one on: anyone with the link sees the games, results
-- and table (names only). Off is NULL; turning it on again makes a new link.
ALTER TABLE events ADD COLUMN public_slug text CHECK (public_slug ~ '^[a-z0-9-]{3,80}$');
CREATE UNIQUE INDEX events_public_slug ON events (public_slug) WHERE public_slug IS NOT NULL;
