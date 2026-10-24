-- A host who hasn't added the score is reminded: once soon after the game ends, and once more the
-- next morning if it's still missing. Then no more. One row per game, whatever runs twice.
CREATE TABLE result_reminders (
    game_id        uuid        PRIMARY KEY REFERENCES games (id) ON DELETE CASCADE,
    ended_at       timestamptz NOT NULL,
    first_sent_at  timestamptz NOT NULL,
    second_sent_at timestamptz
);
