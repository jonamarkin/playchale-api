-- Corporate league operations. This layer is deliberately additive: personal competitions keep
-- their current lifecycle, while organisation competitions opt into draft/publish operations.

CREATE TABLE organisations (
    id                 uuid        PRIMARY KEY,
    name               text        NOT NULL,
    slug               text        NOT NULL,
    country            char(2)     NOT NULL DEFAULT 'GH',
    primary_colour     text        NOT NULL DEFAULT '#16332d',
    logo                bytea,
    logo_content_type   text,
    logo_version        int         NOT NULL DEFAULT 0,
    corporate_enabled  boolean     NOT NULL DEFAULT false,
    created_by         uuid        NOT NULL REFERENCES users (id),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT organisations_slug_format CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT organisations_colour_format CHECK (primary_colour ~ '^#[0-9a-fA-F]{6}$'),
    CONSTRAINT organisations_logo_pair CHECK ((logo IS NULL) = (logo_content_type IS NULL))
);

CREATE UNIQUE INDEX organisations_slug_unique ON organisations (lower(slug));

CREATE TABLE organisation_memberships (
    organisation_id uuid        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role             text        NOT NULL CHECK (role IN ('owner', 'admin')),
    created_at       timestamptz NOT NULL,
    created_by       uuid        NOT NULL REFERENCES users (id),
    PRIMARY KEY (organisation_id, user_id)
);

CREATE INDEX organisation_memberships_by_user ON organisation_memberships (user_id);

CREATE TABLE organisation_invitations (
    id              uuid        PRIMARY KEY,
    organisation_id uuid        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    role            text        NOT NULL CHECK (role IN ('admin')),
    token_hash      text        NOT NULL UNIQUE,
    invited_by      uuid        NOT NULL REFERENCES users (id),
    expires_at      timestamptz NOT NULL,
    accepted_by     uuid        REFERENCES users (id),
    accepted_at     timestamptz,
    created_at      timestamptz NOT NULL,
    CHECK ((accepted_by IS NULL) = (accepted_at IS NULL))
);

ALTER TABLE competitions
    ADD COLUMN organisation_id uuid REFERENCES organisations (id),
    ADD COLUMN schedule_status text NOT NULL DEFAULT 'published'
        CHECK (schedule_status IN ('none', 'draft', 'published'));

CREATE INDEX competitions_by_organisation ON competitions (organisation_id) WHERE organisation_id IS NOT NULL;

CREATE TABLE competition_staff (
    competition_id uuid        NOT NULL REFERENCES competitions (id) ON DELETE CASCADE,
    user_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role           text        NOT NULL CHECK (role IN ('manager')),
    added_by       uuid        NOT NULL REFERENCES users (id),
    added_at       timestamptz NOT NULL,
    PRIMARY KEY (competition_id, user_id, role)
);

CREATE TABLE competition_entry_managers (
    competition_id uuid        NOT NULL,
    team_id        uuid        NOT NULL,
    user_id        uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    added_by       uuid        NOT NULL REFERENCES users (id),
    added_at       timestamptz NOT NULL,
    PRIMARY KEY (competition_id, team_id, user_id),
    FOREIGN KEY (competition_id, team_id)
        REFERENCES competition_entries (competition_id, team_id) ON DELETE CASCADE
);

CREATE TABLE competition_locations (
    id              uuid        PRIMARY KEY,
    competition_id  uuid        NOT NULL REFERENCES competitions (id) ON DELETE CASCADE,
    name            text        NOT NULL,
    area            text,
    map_url         text,
    venue_id        uuid        REFERENCES venues (id),
    pitch_id        uuid        REFERENCES pitches (id),
    created_at      timestamptz NOT NULL,
    CHECK ((pitch_id IS NULL) OR (venue_id IS NOT NULL))
);

CREATE INDEX competition_locations_by_competition ON competition_locations (competition_id);

ALTER TABLE games ADD COLUMN competition_location_id uuid REFERENCES competition_locations (id);

CREATE TABLE fixture_officials (
    game_id      uuid        PRIMARY KEY REFERENCES games (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    assigned_by  uuid        NOT NULL REFERENCES users (id),
    assigned_at  timestamptz NOT NULL
);

CREATE INDEX fixture_officials_by_user ON fixture_officials (user_id);

CREATE TABLE roster_members (
    id                    uuid        PRIMARY KEY,
    competition_id        uuid        NOT NULL,
    team_id               uuid        NOT NULL,
    display_name          text        NOT NULL,
    employee_reference    text,
    user_id               uuid        REFERENCES users (id),
    eligibility_state     text        NOT NULL DEFAULT 'draft'
        CHECK (eligibility_state IN ('draft', 'submitted', 'approved', 'rejected')),
    attested_by           uuid        REFERENCES users (id),
    attested_at           timestamptz,
    reviewed_by           uuid        REFERENCES users (id),
    reviewed_at           timestamptz,
    review_note           text,
    claim_token_hash      text        UNIQUE,
    created_by            uuid        NOT NULL REFERENCES users (id),
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    FOREIGN KEY (competition_id, team_id)
        REFERENCES competition_entries (competition_id, team_id) ON DELETE CASCADE,
    CHECK ((attested_by IS NULL) = (attested_at IS NULL)),
    CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL))
);

CREATE UNIQUE INDEX roster_member_user_once
    ON roster_members (competition_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX roster_members_by_entry ON roster_members (competition_id, team_id, created_at);

-- Existing named squads remain valid corporate roster members after a competition is enabled.
INSERT INTO roster_members (id, competition_id, team_id, display_name, user_id, eligibility_state,
                            attested_by, attested_at, reviewed_by, reviewed_at, created_by, created_at, updated_at)
SELECT gen_random_uuid(), ep.competition_id, ep.team_id, u.name, ep.user_id, 'approved',
       c.organiser_id, ep.added_at, c.organiser_id, ep.added_at, c.organiser_id, ep.added_at, ep.added_at
FROM entry_players ep
JOIN users u ON u.id = ep.user_id
JOIN competitions c ON c.id = ep.competition_id;

CREATE TABLE competition_entry_finance (
    competition_id uuid        NOT NULL,
    team_id        uuid        NOT NULL,
    amount_due     bigint      NOT NULL DEFAULT 0 CHECK (amount_due >= 0),
    status         text        NOT NULL DEFAULT 'unpaid' CHECK (status IN ('unpaid', 'paid', 'waived')),
    method         text        CHECK (method IN ('bank-transfer', 'momo', 'cash', 'other')),
    reference      text,
    paid_at        timestamptz,
    private_note   text,
    updated_by     uuid        NOT NULL REFERENCES users (id),
    updated_at     timestamptz NOT NULL,
    PRIMARY KEY (competition_id, team_id),
    FOREIGN KEY (competition_id, team_id)
        REFERENCES competition_entries (competition_id, team_id) ON DELETE CASCADE
);

CREATE TABLE competition_announcements (
    id                 uuid        PRIMARY KEY,
    competition_id     uuid        NOT NULL REFERENCES competitions (id) ON DELETE CASCADE,
    audience           text        NOT NULL CHECK (audience IN ('everyone', 'staff', 'team')),
    team_id            uuid        REFERENCES teams (id),
    title              text        NOT NULL,
    body               text        NOT NULL,
    acknowledgement    boolean     NOT NULL DEFAULT false,
    published_by       uuid        NOT NULL REFERENCES users (id),
    published_at       timestamptz NOT NULL,
    CHECK ((audience = 'team') = (team_id IS NOT NULL))
);

CREATE TABLE announcement_recipients (
    announcement_id uuid        NOT NULL REFERENCES competition_announcements (id) ON DELETE CASCADE,
    user_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    acknowledged_at  timestamptz,
    PRIMARY KEY (announcement_id, user_id)
);

CREATE TABLE fixture_match_sheets (
    game_id       uuid        PRIMARY KEY REFERENCES games (id) ON DELETE CASCADE,
    home_score    int         NOT NULL DEFAULT 0 CHECK (home_score >= 0),
    away_score    int         NOT NULL DEFAULT 0 CHECK (away_score >= 0),
    notes         text,
    status        text        NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'submitted')),
    saved_by      uuid        NOT NULL REFERENCES users (id),
    saved_at      timestamptz NOT NULL,
    submitted_by  uuid        REFERENCES users (id),
    submitted_at  timestamptz,
    version       int         NOT NULL DEFAULT 1,
    CHECK ((submitted_by IS NULL) = (submitted_at IS NULL))
);

CREATE TABLE match_sheet_players (
    game_id          uuid        NOT NULL REFERENCES fixture_match_sheets (game_id) ON DELETE CASCADE,
    roster_member_id uuid        NOT NULL REFERENCES roster_members (id),
    team_id          uuid        NOT NULL REFERENCES teams (id),
    participation    text        NOT NULL CHECK (participation IN ('starter', 'substitute', 'did-not-play')),
    checked_in       boolean     NOT NULL DEFAULT false,
    goals            int         NOT NULL DEFAULT 0 CHECK (goals >= 0),
    assists          int         NOT NULL DEFAULT 0 CHECK (assists >= 0),
    PRIMARY KEY (game_id, roster_member_id)
);

CREATE TABLE match_sheet_cards (
    id                uuid PRIMARY KEY,
    game_id           uuid NOT NULL REFERENCES fixture_match_sheets (game_id) ON DELETE CASCADE,
    roster_member_id  uuid NOT NULL REFERENCES roster_members (id),
    colour            text NOT NULL CHECK (colour IN ('yellow', 'red')),
    minute            int CHECK (minute BETWEEN 0 AND 300),
    note              text
);

CREATE TABLE match_sheet_revisions (
    id             uuid        PRIMARY KEY,
    game_id        uuid        NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    version        int         NOT NULL,
    snapshot       jsonb       NOT NULL,
    reason         text        NOT NULL,
    corrected_by   uuid        NOT NULL REFERENCES users (id),
    corrected_at   timestamptz NOT NULL,
    UNIQUE (game_id, version)
);

CREATE TABLE audit_events (
    id              uuid        PRIMARY KEY,
    organisation_id uuid        NOT NULL REFERENCES organisations (id) ON DELETE CASCADE,
    competition_id  uuid        REFERENCES competitions (id) ON DELETE CASCADE,
    actor_id        uuid        NOT NULL REFERENCES users (id),
    event_type      text        NOT NULL,
    subject_type    text        NOT NULL,
    subject_id      uuid,
    details         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     timestamptz NOT NULL
);

CREATE INDEX audit_events_stream ON audit_events (organisation_id, occurred_at DESC);
CREATE INDEX audit_events_by_competition ON audit_events (competition_id, occurred_at DESC)
    WHERE competition_id IS NOT NULL;

-- The audit stream is append-only even for application roles with broad table privileges.
CREATE FUNCTION reject_audit_event_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit events are immutable';
END;
$$;

CREATE TRIGGER audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_event_change();

