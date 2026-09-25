-- Counters for rate limits (e.g. sign-in texts per connection, per day), kept in Postgres so the
-- limits hold however many copies of the API are running. Fixed windows: one row per key per window.
CREATE TABLE rate_limits (
    key          text        NOT NULL,
    window_start timestamptz NOT NULL,
    count        int         NOT NULL,
    PRIMARY KEY (key, window_start)
);

-- The clean-up job removes windows that have ended.
CREATE INDEX rate_limits_by_window ON rate_limits (window_start);
