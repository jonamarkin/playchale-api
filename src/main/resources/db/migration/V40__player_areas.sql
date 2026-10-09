-- More than one place someone plays: around home, near work, where their friends are. The first is
-- their main one, and `area` keeps a copy of it for anything that reads a single place.
ALTER TABLE users ADD COLUMN areas text[] NOT NULL DEFAULT '{}';
UPDATE users SET areas = ARRAY[btrim(area)] WHERE area IS NOT NULL AND btrim(area) <> '';
ALTER TABLE users ADD CONSTRAINT users_areas_count CHECK (cardinality(areas) <= 3);

COMMENT ON COLUMN users.areas IS 'Where they usually play, main one first (up to 3). users.area is the first of them.';
