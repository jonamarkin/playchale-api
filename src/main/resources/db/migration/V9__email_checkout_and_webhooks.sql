-- For paying through Paystack's hosted checkout.

-- Where a player's receipts go. Asked for the first time they pay; only ever shown to them.
ALTER TABLE users ADD COLUMN email text;

-- The provider's checkout page for a payment, so a player can go back to it.
ALTER TABLE payments ADD COLUMN checkout_url text;

-- Webhooks the payment provider has sent us, kept by a hash of their body so a retried delivery is
-- handled once. A webhook only ever prompts us to ask the provider; it's never taken as the answer.
CREATE TABLE webhook_events (
    provider    text        NOT NULL,
    body_hash   text        NOT NULL,
    event       text        NOT NULL,
    reference   text,
    received_at timestamptz NOT NULL,
    PRIMARY KEY (provider, body_hash)
);
