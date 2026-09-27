-- Team-vs-team friendlies: a game between two standing teams outside any league. It uses the same
-- home_team_id / away_team_id as league fixtures, with competition_id null. The other team's captain
-- accepts the challenge before any of their players are asked, so nobody can fill another team's
-- notifications uninvited.
ALTER TABLE games ADD COLUMN opponent_status text CHECK (opponent_status IN ('pending', 'accepted', 'declined'));
ALTER TABLE games ADD CONSTRAINT games_friendly
    CHECK (opponent_status IS NULL OR (competition_id IS NULL AND home_team_id IS NOT NULL AND away_team_id IS NOT NULL));

-- Which side a player is on in a friendly, so the result form knows the teams. League fixtures take
-- sides from their squads instead, and pickup games have none.
ALTER TABLE game_participants ADD COLUMN team_id uuid;

-- A team's games, for its record and what's coming up.
CREATE INDEX games_by_home_team ON games (home_team_id) WHERE home_team_id IS NOT NULL;
CREATE INDEX games_by_away_team ON games (away_team_id) WHERE away_team_id IS NOT NULL;
