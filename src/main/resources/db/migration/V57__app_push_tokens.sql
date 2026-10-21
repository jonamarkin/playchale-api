-- Phones with the PlayChale app: its notifications go through Expo's push service to each one. A
-- token moves to whoever signs in on that phone next, and goes when the app is removed (Expo says
-- so) or the person signs out.
CREATE TABLE app_push_tokens (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token        text        NOT NULL UNIQUE,
    platform     text        NOT NULL CHECK (platform IN ('android', 'ios')),
    created_at   timestamptz NOT NULL,
    last_sent_at timestamptz
);

CREATE INDEX app_push_tokens_by_user ON app_push_tokens (user_id);
