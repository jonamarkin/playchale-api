-- Pools, then a knockout: entries are dealt into pools that each play everyone once, and the best
-- of each pool go through to a knockout. A pool match carries its pool's number; the knockout's
-- matches carry none, so the same round and slot can be in a pool and in the knockout.
ALTER TABLE event_games
    DROP CONSTRAINT event_games_format_check,
    ADD CONSTRAINT event_games_format_check CHECK (format IN ('knockout', 'league', 'placings', 'pools')),
    ADD COLUMN pool_size        smallint CHECK (pool_size BETWEEN 2 AND 16),
    ADD COLUMN advance_per_pool smallint CHECK (advance_per_pool BETWEEN 1 AND 8),
    ADD CONSTRAINT event_games_pools CHECK (format <> 'pools' OR (pool_size IS NOT NULL AND advance_per_pool IS NOT NULL));

ALTER TABLE event_entries ADD COLUMN pool smallint CHECK (pool >= 1);

ALTER TABLE event_matches
    ADD COLUMN pool smallint CHECK (pool >= 1),
    DROP CONSTRAINT event_matches_game_id_round_slot_third_place_key,
    ADD CONSTRAINT event_matches_place_in_game UNIQUE NULLS NOT DISTINCT (game_id, pool, round, slot, third_place);
