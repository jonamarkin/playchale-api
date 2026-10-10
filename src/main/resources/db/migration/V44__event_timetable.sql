-- A game's plan for the day: each match or heat takes match_minutes, played across these places at
-- once (courts, tables, boards). Each match and heat keeps its own time and place, filled from it.
ALTER TABLE event_games
    ADD COLUMN match_minutes smallint CHECK (match_minutes BETWEEN 5 AND 600),
    ADD COLUMN locations     text[]   CHECK (cardinality(locations) BETWEEN 1 AND 12);
