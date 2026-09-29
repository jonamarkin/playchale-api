-- Which Terms and Privacy Policy each player agreed to, and when. Null until they do: everyone who
-- signed up before this is asked once, the next time they open the app.
ALTER TABLE users ADD COLUMN terms_version text;
ALTER TABLE users ADD COLUMN terms_accepted_at timestamptz;
