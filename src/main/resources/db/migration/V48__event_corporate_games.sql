-- Corporate games days: thirty companies on one day, each paying to take part, each registering its
-- own staff, with sponsors on the screens.

-- What each group (a company, a house) pays the organisers to take part, in the event's money, minor
-- units. Tracked, never collected: PlayChale never holds it. Alongside the per-person entry fee.
ALTER TABLE events ADD COLUMN group_fee bigint CHECK (group_fee IS NULL OR group_fee > 0);

-- Whether a group has paid it, and how: cash, MoMo, or a bank transfer (as companies often pay).
-- rep_code is the link a group's own rep signs up with.
ALTER TABLE event_groups
    ADD COLUMN fee_paid_via  text        CHECK (fee_paid_via IN ('cash', 'momo', 'bank')),
    ADD COLUMN fee_paid_at   timestamptz,
    ADD COLUMN fee_marked_by uuid        REFERENCES users (id),
    ADD COLUMN rep_code      text,
    ADD CONSTRAINT event_groups_fee_paid CHECK ((fee_paid_via IS NULL) = (fee_paid_at IS NULL));

UPDATE event_groups SET rep_code = substr(replace(gen_random_uuid()::text, '-', ''), 1, 12);
ALTER TABLE event_groups ALTER COLUMN rep_code SET DEFAULT substr(replace(gen_random_uuid()::text, '-', ''), 1, 12),
                         ALTER COLUMN rep_code SET NOT NULL;
CREATE UNIQUE INDEX event_groups_rep_code ON event_groups (rep_code);

-- A group's reps: someone from the company who registers its people and enters them in games, for
-- their own group only, while sign-ups are open.
CREATE TABLE event_group_reps (
    group_id uuid        NOT NULL REFERENCES event_groups (id) ON DELETE CASCADE,
    user_id  uuid        NOT NULL REFERENCES users (id),
    added_at timestamptz NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX event_group_reps_by_user ON event_group_reps (user_id);

-- People a rep added, as against an admin or the join link.
ALTER TABLE event_people DROP CONSTRAINT event_people_source_check;
ALTER TABLE event_people ADD CONSTRAINT event_people_source_check CHECK (source IN ('admin', 'link', 'rep'));

-- The event's sponsors: on the public page, the board and the event page. One may be the headline
-- sponsor. Logos are kept in the database, as workspace logos are.
CREATE TABLE event_sponsors (
    id                uuid        PRIMARY KEY,
    event_id          uuid        NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    name              text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60),
    headline          boolean     NOT NULL DEFAULT false,
    position          int         NOT NULL,
    logo              bytea,
    logo_content_type text,
    logo_version      int         NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL,
    CONSTRAINT event_sponsors_logo_pair CHECK ((logo IS NULL) = (logo_content_type IS NULL))
);

CREATE INDEX event_sponsors_by_event ON event_sponsors (event_id, position);
CREATE UNIQUE INDEX event_sponsors_one_headline ON event_sponsors (event_id) WHERE headline;
