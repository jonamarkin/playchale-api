-- Payments players make in the app, and the ledger of money moving between people.
-- PlayChale never holds money, so there is no balance anywhere: a statement is the list of
-- movements, not a wallet.

-- One attempt to collect a player's share through the payment provider.
CREATE TABLE payments (
    id             uuid        PRIMARY KEY,
    game_id        uuid        NOT NULL REFERENCES games (id),
    user_id        uuid        NOT NULL REFERENCES users (id),
    method         text        NOT NULL CHECK (method IN ('momo-mtn', 'momo-telecel', 'momo-at', 'card')),
    -- The player's share, and the processing fee on top, in minor units of currency.
    amount         bigint      NOT NULL CHECK (amount > 0),
    fee            bigint      NOT NULL DEFAULT 0 CHECK (fee >= 0),
    currency       char(3)     NOT NULL,
    status         text        NOT NULL CHECK (status IN ('pending', 'succeeded', 'failed')),
    -- Ours, shown on the receipt and sent to the provider, so asking twice can't charge twice.
    reference      text        NOT NULL UNIQUE,
    -- The mobile money number charged (E.164). Absent for cards.
    payer_phone    text,
    failure_reason text,
    created_at     timestamptz NOT NULL,
    settled_at     timestamptz
);

CREATE INDEX payments_by_user ON payments (user_id, created_at DESC);
-- Pending payments, for the worker that will chase them with the provider.
CREATE INDEX payments_pending ON payments (created_at) WHERE status = 'pending';

-- One movement of money from one person's point of view. Append-only: a mistake is corrected with
-- another movement (a refund), never by editing one.
CREATE TABLE movements (
    id              uuid        PRIMARY KEY,
    -- Whose statement this line is on.
    user_id         uuid        NOT NULL REFERENCES users (id),
    direction       text        NOT NULL CHECK (direction IN ('in', 'out')),
    kind            text        NOT NULL CHECK (kind IN ('share', 'payout', 'refund')),
    amount          bigint      NOT NULL CHECK (amount > 0),
    currency        char(3)     NOT NULL,
    game_id         uuid        REFERENCES games (id),
    -- The person on the other side of it, if they have an account.
    counterparty_id uuid        REFERENCES users (id),
    -- A payment method, or 'cash' handed over in person.
    method          text        CHECK (method IN ('momo-mtn', 'momo-telecel', 'momo-at', 'card', 'cash')),
    reference       text,
    payment_id      uuid        REFERENCES payments (id),
    status          text        NOT NULL CHECK (status IN ('settled', 'pending', 'failed')),
    created_at      timestamptz NOT NULL
);

CREATE INDEX movements_by_user ON movements (user_id, created_at DESC);
