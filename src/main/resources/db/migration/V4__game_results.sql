-- Results: the score the host records, who played on which side and what they scored, and what
-- the other players say about it.

CREATE TABLE game_results (
    game_id     uuid        PRIMARY KEY REFERENCES games (id) ON DELETE CASCADE,
    -- For set-based sports (volleyball, tennis) these are sets won; the sets are in result_sets.
    home_score  int         NOT NULL CHECK (home_score >= 0),
    away_score  int         NOT NULL CHECK (away_score >= 0),
    -- The host, who recorded it.
    recorded_by uuid        NOT NULL REFERENCES users (id),
    recorded_at timestamptz NOT NULL
);

-- Each person in the game: which side they played on (the host's side is "home"), or that they
-- didn't turn up, and what they scored. Guests have no account, so they're kept by their spot's key
-- ("guest:<spot>") and earn nobody a record.
CREATE TABLE result_players (
    game_id    uuid NOT NULL REFERENCES game_results (game_id) ON DELETE CASCADE,
    player_key text NOT NULL,
    user_id    uuid REFERENCES users (id),
    side       text NOT NULL CHECK (side IN ('home', 'away', 'absent')),
    goals      int  NOT NULL DEFAULT 0 CHECK (goals >= 0),
    assists    int  NOT NULL DEFAULT 0 CHECK (assists >= 0),
    points     int  NOT NULL DEFAULT 0 CHECK (points >= 0),
    PRIMARY KEY (game_id, player_key)
);

-- A player's record is read from here, so it's indexed by player.
CREATE INDEX result_players_by_user ON result_players (user_id) WHERE user_id IS NOT NULL;

-- Set by set, home side first (set-based sports only).
CREATE TABLE result_sets (
    game_id uuid NOT NULL REFERENCES game_results (game_id) ON DELETE CASCADE,
    number  int  NOT NULL CHECK (number >= 1),
    home    int  NOT NULL CHECK (home >= 0),
    away    int  NOT NULL CHECK (away >= 0),
    PRIMARY KEY (game_id, number)
);

-- Players who checked the result and said it's right.
CREATE TABLE result_confirmations (
    game_id      uuid        NOT NULL REFERENCES game_results (game_id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id),
    confirmed_at timestamptz NOT NULL,
    PRIMARY KEY (game_id, user_id)
);

-- Players who say it isn't, in their words. The host can correct it.
CREATE TABLE result_disputes (
    game_id     uuid        NOT NULL REFERENCES game_results (game_id) ON DELETE CASCADE,
    user_id     uuid        NOT NULL REFERENCES users (id),
    reason      text,
    disputed_at timestamptz NOT NULL,
    PRIMARY KEY (game_id, user_id)
);
