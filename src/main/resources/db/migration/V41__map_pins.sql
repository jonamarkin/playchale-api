-- Where a venue, a game somewhere that isn't a partner venue, a repeating game and an event are on
-- the map: a pin. Shown on a map on their pages, and used to sort Discover by distance.
--
-- pin_source says whose coordinates they are, which decides how long they may be kept:
--   place: a place picked from Google's place search, as Google has it. Google allows keeping the
--          place ID for good but the coordinates for at most 30 days, so a daily job looks them up
--          again (or clears them) before then. pinned_at is when they were last looked up.
--   own:   a spot someone set themselves: the map moved under the pin, their phone's location, or
--          coordinates they typed. Ours to keep.
-- A place pin whose coordinates couldn't be looked up again keeps its place ID, without coordinates.

ALTER TABLE venues
    ADD COLUMN latitude   double precision,
    ADD COLUMN longitude  double precision,
    ADD COLUMN place_id   text,
    ADD COLUMN pin_source text,
    ADD COLUMN pinned_at  timestamptz;

ALTER TABLE games
    ADD COLUMN latitude   double precision,
    ADD COLUMN longitude  double precision,
    ADD COLUMN place_id   text,
    ADD COLUMN pin_source text,
    ADD COLUMN pinned_at  timestamptz;

ALTER TABLE game_series
    ADD COLUMN latitude   double precision,
    ADD COLUMN longitude  double precision,
    ADD COLUMN place_id   text,
    ADD COLUMN pin_source text,
    ADD COLUMN pinned_at  timestamptz;

ALTER TABLE events
    ADD COLUMN latitude   double precision,
    ADD COLUMN longitude  double precision,
    ADD COLUMN place_id   text,
    ADD COLUMN pin_source text,
    ADD COLUMN pinned_at  timestamptz;

ALTER TABLE venues
    ADD CONSTRAINT venues_pin CHECK (
        (latitude IS NULL) = (longitude IS NULL)
        AND (latitude IS NULL OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
        AND (pin_source IS NULL OR pin_source IN ('place', 'own'))
        AND (place_id IS NULL OR length(place_id) <= 300)
        AND (pin_source IS NOT NULL OR (latitude IS NULL AND place_id IS NULL)));
ALTER TABLE games
    ADD CONSTRAINT games_pin CHECK (
        (latitude IS NULL) = (longitude IS NULL)
        AND (latitude IS NULL OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
        AND (pin_source IS NULL OR pin_source IN ('place', 'own'))
        AND (place_id IS NULL OR length(place_id) <= 300)
        AND (pin_source IS NOT NULL OR (latitude IS NULL AND place_id IS NULL)));
ALTER TABLE game_series
    ADD CONSTRAINT game_series_pin CHECK (
        (latitude IS NULL) = (longitude IS NULL)
        AND (latitude IS NULL OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
        AND (pin_source IS NULL OR pin_source IN ('place', 'own'))
        AND (place_id IS NULL OR length(place_id) <= 300)
        AND (pin_source IS NOT NULL OR (latitude IS NULL AND place_id IS NULL)));
ALTER TABLE events
    ADD CONSTRAINT events_pin CHECK (
        (latitude IS NULL) = (longitude IS NULL)
        AND (latitude IS NULL OR (latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
        AND (pin_source IS NULL OR pin_source IN ('place', 'own'))
        AND (place_id IS NULL OR length(place_id) <= 300)
        AND (pin_source IS NOT NULL OR (latitude IS NULL AND place_id IS NULL)));

-- What the daily job looks at: place pins, oldest first.
CREATE INDEX venues_place_pins      ON venues (pinned_at)      WHERE pin_source = 'place';
CREATE INDEX games_place_pins       ON games (pinned_at)       WHERE pin_source = 'place';
CREATE INDEX game_series_place_pins ON game_series (pinned_at) WHERE pin_source = 'place';
CREATE INDEX events_place_pins      ON events (pinned_at)      WHERE pin_source = 'place';

-- Links the app made from someone's location ("Use my current location") or typed coordinates
-- already hold a spot of their own: https://www.google.com/maps/search/?api=1&query=5.6037,-0.187
UPDATE venues SET latitude = split_part(substring(map_url FROM 'query=(.*)$'), ',', 1)::double precision,
                  longitude = split_part(substring(map_url FROM 'query=(.*)$'), ',', 2)::double precision,
                  pin_source = 'own', pinned_at = now()
 WHERE map_url ~ '^https://www\.google\.com/maps/search/\?api=1&query=-?[0-9]{1,2}(\.[0-9]+)?,-?[0-9]{1,3}(\.[0-9]+)?$';
UPDATE games SET latitude = split_part(substring(map_url FROM 'query=(.*)$'), ',', 1)::double precision,
                 longitude = split_part(substring(map_url FROM 'query=(.*)$'), ',', 2)::double precision,
                 pin_source = 'own', pinned_at = now()
 WHERE map_url ~ '^https://www\.google\.com/maps/search/\?api=1&query=-?[0-9]{1,2}(\.[0-9]+)?,-?[0-9]{1,3}(\.[0-9]+)?$';
UPDATE game_series SET latitude = split_part(substring(map_url FROM 'query=(.*)$'), ',', 1)::double precision,
                       longitude = split_part(substring(map_url FROM 'query=(.*)$'), ',', 2)::double precision,
                       pin_source = 'own', pinned_at = now()
 WHERE map_url ~ '^https://www\.google\.com/maps/search/\?api=1&query=-?[0-9]{1,2}(\.[0-9]+)?,-?[0-9]{1,3}(\.[0-9]+)?$';
UPDATE events SET latitude = split_part(substring(map_url FROM 'query=(.*)$'), ',', 1)::double precision,
                  longitude = split_part(substring(map_url FROM 'query=(.*)$'), ',', 2)::double precision,
                  pin_source = 'own', pinned_at = now()
 WHERE map_url ~ '^https://www\.google\.com/maps/search/\?api=1&query=-?[0-9]{1,2}(\.[0-9]+)?,-?[0-9]{1,3}(\.[0-9]+)?$';

COMMENT ON COLUMN venues.pin_source IS 'place: Google place search coordinates, looked up again within 30 days (place_id kept for good). own: set by a person, kept.';
COMMENT ON COLUMN games.pin_source IS 'As venues.pin_source. Only for a game anywhere but a partner venue: a partner venue''s own pin is used.';
COMMENT ON COLUMN game_series.pin_source IS 'As venues.pin_source; copied to each game it opens.';
COMMENT ON COLUMN events.pin_source IS 'As venues.pin_source.';
