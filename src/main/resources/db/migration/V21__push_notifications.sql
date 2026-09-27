-- Phone notifications (Web Push). Each browser that allows them gives a subscription: where to send
-- (a URL on the browser's push service) and the keys to encrypt for it. Someone can have several.
CREATE TABLE push_subscriptions (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- One browser, one subscription: a phone that changes hands moves to whoever signs in on it.
    endpoint     text        NOT NULL UNIQUE,
    p256dh       text        NOT NULL,
    auth         text        NOT NULL,
    created_at   timestamptz NOT NULL,
    last_sent_at timestamptz
);

CREATE INDEX push_subscriptions_by_user ON push_subscriptions (user_id);

-- Kinds of notification someone doesn't want on their phone. They still see them in the app.
CREATE TABLE push_preferences (
    user_id uuid   PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    muted   text[] NOT NULL DEFAULT '{}'
);
