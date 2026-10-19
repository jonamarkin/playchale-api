-- A reminder the evening before a game: in the app (and a push) for everyone in it, and a text to
-- every Ghanaian number in it, guests included.

-- One reminder per game per kick-off, whatever runs twice. A game moved to another day has another
-- kick-off, so it's reminded again for the new one.
CREATE TABLE game_reminders (
    game_id   uuid        PRIMARY KEY REFERENCES games (id) ON DELETE CASCADE,
    starts_at timestamptz NOT NULL,
    sent_at   timestamptz NOT NULL
);

-- A member's "Text me the evening before my games". No row means yes.
CREATE TABLE text_preferences (
    user_id   uuid    PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    reminders boolean NOT NULL
);
