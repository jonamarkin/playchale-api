-- Who dropped out of a game, and how much notice they gave.
--
-- Until now a spot that was given up simply vanished: game_participants lost the row and nothing was
-- left to say it had ever been there. That made the most telling thing anyone does on PlayChale --
-- pulling out the night before -- indistinguishable from never having joined, so a host had no way
-- of knowing, and we had no way of ever working it out afterwards.
--
-- It is kept apart from game_participants on purpose. That table answers "who is in this game", and
-- every count in the app (spots left, who has paid, whether it is full) reads it. Marking rows dead
-- in place would mean every one of those counts had to remember to skip them, and the first one that
-- forgot would quietly hold a spot for someone who had already gone. This table answers a different
-- question -- "what happened" -- and nothing about running a game reads it.
--
-- See RELIABILITY.md for why this is a record and not a score.
CREATE TABLE game_departures (
    id              uuid        PRIMARY KEY,
    game_id         uuid        NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at       timestamptz NOT NULL,
    left_at         timestamptz NOT NULL,
    -- Kick-off as it stood when they left, and the notice that gave. Both are settled here rather
    -- than worked out later from the game, because a host can move a game afterwards: someone who
    -- pulled out a fortnight early would otherwise look like they left an hour before, or the other
    -- way round. Negative notice means they left after kick-off had passed.
    starts_at       timestamptz NOT NULL,
    notice_minutes  integer     NOT NULL,
    -- 'left'    they gave the spot up themselves -- the only one that says anything about them
    -- 'removed' the host took them off, which is the host's doing, not theirs
    reason          text        NOT NULL CHECK (reason IN ('left', 'removed')),
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- Reading someone's history, newest first.
CREATE INDEX game_departures_user ON game_departures (user_id, left_at DESC);
CREATE INDEX game_departures_game ON game_departures (game_id);
