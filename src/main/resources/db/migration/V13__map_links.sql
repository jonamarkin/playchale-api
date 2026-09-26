-- Where to find a place: a map link for directions, from Google Maps, Apple Maps or Waze (checked by
-- shared/maps/MapLink). Optional everywhere.
--
-- A partner venue keeps its own; games and leagues there show it (looked up, so a pin the owner
-- adds or fixes later reaches every game). Games and leagues anywhere else keep the one their host
-- or organiser added.
ALTER TABLE venues       ADD COLUMN map_url text;
ALTER TABLE games        ADD COLUMN map_url text;
ALTER TABLE competitions ADD COLUMN map_url text;
