-- Standing teams: a team exists on its own (a crew, a club, a school's side) and can be entered into
-- leagues, invited to games and play friendlies. Until now a team lived inside one league.
--
-- Existing league teams become standing teams with the SAME ids, so fixtures (games.home_team_id,
-- away_team_id) and their results stay linked. Each league's squad is kept as that team's entry.

-- Who is in each team. A team's captain may or may not play: an organiser running a school's team
-- is its captain without being a member.
CREATE TABLE team_members (
    team_id   uuid        NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    user_id   uuid        NOT NULL REFERENCES users (id),
    joined_at timestamptz NOT NULL,
    PRIMARY KEY (team_id, user_id)
);

CREATE INDEX team_members_by_user ON team_members (user_id);

INSERT INTO team_members (team_id, user_id, joined_at)
SELECT team_id, user_id, min(added_at) FROM team_players GROUP BY team_id, user_id;

-- A team in a league. 'invited' until the team's captain accepts (when the organiser isn't its captain).
CREATE TABLE competition_entries (
    competition_id uuid        NOT NULL REFERENCES competitions (id) ON DELETE CASCADE,
    team_id        uuid        NOT NULL REFERENCES teams (id) ON DELETE CASCADE,
    status         text        NOT NULL CHECK (status IN ('invited', 'entered')),
    entered_at     timestamptz NOT NULL,
    PRIMARY KEY (competition_id, team_id)
);

CREATE INDEX competition_entries_by_team ON competition_entries (team_id);

INSERT INTO competition_entries (competition_id, team_id, status, entered_at)
SELECT competition_id, id, 'entered', created_at FROM teams;

-- The league squad: who plays for a team in one league. Still one team per player per league
-- (the UNIQUE (competition_id, user_id) it already has). May be empty: schools' leagues often
-- don't list players.
ALTER TABLE team_players RENAME TO entry_players;
ALTER TABLE entry_players DROP CONSTRAINT team_players_pkey;
ALTER TABLE entry_players ADD PRIMARY KEY (competition_id, team_id, user_id);
ALTER TABLE entry_players ADD CONSTRAINT entry_players_entry
    FOREIGN KEY (competition_id, team_id) REFERENCES competition_entries (competition_id, team_id) ON DELETE CASCADE;

-- Teams no longer belong to one league. Names are unique within a league (checked by the service);
-- two teams elsewhere can share a name.
DROP INDEX teams_name_unique;
ALTER TABLE teams DROP COLUMN competition_id;

-- Asking to join is asking the team, whichever league it's in.
ALTER TABLE join_requests RENAME TO team_join_requests;
ALTER TABLE team_join_requests DROP COLUMN competition_id;
