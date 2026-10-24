-- "Still playing?": someone not seen for a fortnight is told about the games near them, once, and
-- not again for two months. One row per person, kept so a second run (or a second copy of the API)
-- sends nothing.
CREATE TABLE come_back_nudges (
    user_id uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    sent_at timestamptz NOT NULL,
    games   integer     NOT NULL
);
