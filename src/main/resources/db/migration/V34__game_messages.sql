-- Talk about one game, for the people in it.
--
-- Deliberately not a feed. A message belongs to a game, and only the people in that game can read or
-- write it, which is also the whole moderation model: there is no public surface to spam, the
-- audience is the ten people who turn up, and the host can take anything down. A general wall of
-- posts would need reporting, blocking and someone to answer them, none of which exists.
--
-- What it is for is the thing that currently gets lost in a WhatsApp group: "running ten minutes
-- late", "bringing the bibs", "pitch is waterlogged". The group chat keeps the chatter; this keeps
-- what is about this game, next to the game.
CREATE TABLE game_messages (
    id         uuid        PRIMARY KEY,
    game_id    uuid        NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    -- Who said it. Guests hold a spot without an account, so they have nothing to say here.
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body       text        NOT NULL CHECK (length(btrim(body)) BETWEEN 1 AND 500),
    created_at timestamptz NOT NULL
);

-- The only way it is ever read: one game's messages, oldest first.
CREATE INDEX game_messages_game ON game_messages (game_id, created_at);

-- And removed with an account: the users row is only anonymised (V11), so nothing else would clear
-- what someone wrote.
CREATE INDEX game_messages_user ON game_messages (user_id);
