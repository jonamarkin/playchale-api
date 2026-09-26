-- How a game's cost is set. 'split': a total (pitch hire, balls, bibs) shared by the spots, rounded
-- up to the market's step. 'per-player': what each player pays to take part, exactly; total_cost is
-- then that times the spots. Free games are 'split' with a total of 0.
ALTER TABLE games ADD COLUMN pricing text NOT NULL DEFAULT 'split' CHECK (pricing IN ('split', 'per-player'));
