-- Knockouts: a cup played as a bracket, alongside the leagues where everyone plays everyone.
-- (An organisation's multi-sport "Games", grouping several competitions, comes later: competitions
-- stand on their own, so they can be grouped then without moving anything.)
ALTER TABLE competitions ADD COLUMN structure text NOT NULL DEFAULT 'league'
    CHECK (structure IN ('league', 'knockout'));

-- Which tie of its round a fixture is, counted from the top of the bracket, so the winner of tie n
-- meets the winner of its neighbour in the next round. Null for a league fixture.
ALTER TABLE games ADD COLUMN fixture_slot int;
-- A tie that has to produce a winner, so a level score is settled on penalties.
ALTER TABLE games ADD COLUMN fixture_decider boolean NOT NULL DEFAULT false;

-- The shootout, when a knockout tie ends level. Kept apart from the score, so the result stays
-- what happened on the pitch and players' stats with it.
ALTER TABLE game_results ADD COLUMN home_penalties int CHECK (home_penalties >= 0);
ALTER TABLE game_results ADD COLUMN away_penalties int CHECK (away_penalties >= 0);
ALTER TABLE game_results ADD CONSTRAINT game_results_penalties_whole
    CHECK ((home_penalties IS NULL) = (away_penalties IS NULL) AND (home_penalties IS NULL OR home_penalties <> away_penalties));
