-- Whether a league's teams list their players. "expected": teams of players, who join squads and get
-- stats. "optional": schools or organisations, whose teams may play with no players listed; their
-- fixtures then take a score only.
ALTER TABLE competitions ADD COLUMN player_lists text NOT NULL DEFAULT 'expected'
    CHECK (player_lists IN ('expected', 'optional'));
