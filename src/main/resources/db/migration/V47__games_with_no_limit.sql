-- A game (or a repeating one) can be open to any number of players: no capacity. Its cost is then
-- free or a price per player (stored as that price), since a split needs to know how many share it.
ALTER TABLE games ALTER COLUMN capacity DROP NOT NULL;
ALTER TABLE games ADD CONSTRAINT games_spots_for_a_split CHECK (capacity IS NOT NULL OR total_cost = 0 OR pricing = 'per-player');
ALTER TABLE game_series ALTER COLUMN capacity DROP NOT NULL;
ALTER TABLE game_series ADD CONSTRAINT game_series_spots_for_a_split CHECK (capacity IS NOT NULL OR total_cost = 0 OR pricing = 'per-player');
