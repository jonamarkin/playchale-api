-- Player of the match: after a result, everyone who played (with an account) picks someone else who
-- did. Voting closes a day after the result is first recorded, then the players hear who won, once.

-- When voting closes: set with the first result, so the host correcting it later doesn't reopen it.
-- Results from before this have voting closed already.
ALTER TABLE game_results
    ADD COLUMN votes_close_at     timestamptz,
    ADD COLUMN votes_announced_at timestamptz;

UPDATE game_results SET votes_close_at = recorded_at + interval '24 hours';

ALTER TABLE game_results ALTER COLUMN votes_close_at SET NOT NULL;

-- One pick per player per game; picking again replaces it. A pick is a roster key: a player's ID, or
-- "guest:<spot>" for someone without an account. Picks for someone the host later takes off a side
-- simply aren't counted.
CREATE TABLE result_votes (
    game_id    uuid        NOT NULL REFERENCES game_results (game_id) ON DELETE CASCADE,
    voter_id   uuid        NOT NULL REFERENCES users (id),
    player_key text        NOT NULL,
    voted_at   timestamptz NOT NULL,
    PRIMARY KEY (game_id, voter_id)
);

-- The announcement job looks for closed votes not yet announced.
CREATE INDEX game_results_votes_to_announce ON game_results (votes_close_at) WHERE votes_announced_at IS NULL;
