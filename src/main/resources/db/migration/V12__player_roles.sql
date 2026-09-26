-- How each player plays each of their sports: positions for team sports (goalkeeper, guard...),
-- events for athletics later. The choices come from the sport catalogue (catalog/api/SportCatalog),
-- so only their stable ids are stored here.
--
-- Replaces the free-text users.position, which said one thing for every sport. That column is no
-- longer read or written, and goes in a later release (removals come a release after additions).
CREATE TABLE player_roles (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sport   text NOT NULL,
    role    text NOT NULL,
    -- Within a sport, 0 is where they usually play: the one a game's roster shows.
    rank    int  NOT NULL CHECK (rank >= 0),
    PRIMARY KEY (user_id, sport, role)
);

-- For finding players by position ("who plays in goal near Osu?").
CREATE INDEX player_roles_by_role ON player_roles (sport, role);
