-- Who actually turned up.
--
-- The other half of the record V32 started. A drop-out is someone saying in advance that they can't
-- make it, which is the decent thing to do; not turning up at all, having left a spot filled that
-- somebody else wanted, is the thing hosts actually get burned by. Until now nothing in the app
-- could tell the two apart, or tell either from a game that went fine.
--
-- Unlike game_departures this lives on the spot, because that is what it is about and the row is
-- still there afterwards: a played game keeps its participants. Nothing is lost by marking it in
-- place, and nothing that counts a roster reads these columns.
--
-- attended: null until the host says, so "not marked" and "did not turn up" are never confused.
-- Most games will never be marked at all, and that is fine -- the absence of a record is not a
-- record of absence. See RELIABILITY.md.
ALTER TABLE game_participants ADD COLUMN attended boolean;
ALTER TABLE game_participants ADD COLUMN attended_at timestamptz;

-- Marked only once the game has been played, so the two always travel together.
ALTER TABLE game_participants ADD CONSTRAINT game_participants_attendance_marked
    CHECK ((attended IS NULL) = (attended_at IS NULL));

-- Reading someone's turnout, which is always "this player, across their games".
CREATE INDEX game_participants_attended ON game_participants (user_id, attended) WHERE attended IS NOT NULL;
