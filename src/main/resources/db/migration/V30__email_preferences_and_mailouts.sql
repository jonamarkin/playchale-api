-- Email beyond the sign-in code: what someone agreed to receive, and a record of what was sent.
--
-- Two kinds of email, treated differently throughout. Transactional email is about something the
-- person is already in — their game, their roster, their code — and needs no consent. Marketing
-- email (announcements, the weekly digest) is opt-in, off until they turn it on, and every one of
-- them carries a link that turns it off again without signing in.

CREATE TABLE email_preferences (
    user_id      uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- Categories kept out of their inbox, the same groups push uses (V21, PushCategories).
    muted        text[]      NOT NULL DEFAULT '{}',
    -- Off until they say yes. opted_in_at/opted_out_at are the record of when, which is the first
    -- thing a data-protection enquiry asks for; they are kept after an opt-out, not cleared.
    marketing    boolean     NOT NULL DEFAULT false,
    digest       text        NOT NULL DEFAULT 'weekly' CHECK (digest IN ('weekly', 'off')),
    opted_in_at  timestamptz,
    opted_out_at timestamptz,
    -- Lets one tap in an email unsubscribe them with no session. Rotatable: a new value makes every
    -- link in every email already sent stop working.
    token        uuid        NOT NULL DEFAULT gen_random_uuid(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX email_preferences_token ON email_preferences (token);

-- One send. Kept after it finishes: "who was told about this, and when" is a question that gets
-- asked months later, by a member of staff or by the person themselves.
CREATE TABLE mailouts (
    id          uuid        PRIMARY KEY,
    kind        text        NOT NULL CHECK (kind IN ('announcement', 'digest')),
    template    text        NOT NULL,
    subject     text        NOT NULL,
    preheader   text        NOT NULL DEFAULT '',
    -- The template's placeholders, so a send can be rebuilt exactly as it went out.
    payload     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_by  uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

-- One row per person per mailout, written before anything is sent. The primary key is what makes a
-- send idempotent: a resumed or repeated run can never email the same person twice for the same
-- mailout, however it was interrupted.
CREATE TABLE mailout_recipients (
    mailout_id uuid        NOT NULL REFERENCES mailouts (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    email      text        NOT NULL,
    -- 'sending' is claimed-but-not-yet-answered-for. A run that dies mid-send leaves rows there and
    -- never retries them: an email nobody is sure about is better missing than sent twice.
    status     text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sending', 'sent', 'skipped', 'failed')),
    -- Why it was skipped or how it failed. Never the message itself.
    reason     text,
    sent_at    timestamptz,
    PRIMARY KEY (mailout_id, user_id)
);

CREATE INDEX mailout_recipients_pending ON mailout_recipients (mailout_id) WHERE status = 'pending';
