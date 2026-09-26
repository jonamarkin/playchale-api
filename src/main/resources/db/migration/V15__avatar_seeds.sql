-- The face a player picked with Shuffle (a navii seed, drawn by the web app at /faces/v1/<seed>.svg).
-- Null means the face from their account id. A photo, when there is one, is shown instead.
ALTER TABLE users ADD COLUMN avatar_seed text CHECK (avatar_seed ~ '^[A-Za-z0-9_-]{1,40}$');
