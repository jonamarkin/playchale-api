-- More than one person runs a league: a company's sports committee, a school's staff. The owner
-- (competitions.organiser_id) stays the one who can add and remove these; they can do everything else.
CREATE TABLE competition_organisers (
    competition_id uuid        NOT NULL REFERENCES competitions (id) ON DELETE CASCADE,
    user_id        uuid        NOT NULL REFERENCES users (id),
    -- Set by the database: the entity keeps only who, not when.
    added_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (competition_id, user_id)
);

CREATE INDEX competition_organisers_by_user ON competition_organisers (user_id);
