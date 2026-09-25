-- Chasing pending payments with the provider: when to check next, and how many times it's been
-- checked. The worker claims due payments with FOR UPDATE SKIP LOCKED, so several copies of the API
-- can run it without two checking the same payment.
ALTER TABLE payments ADD COLUMN checks int NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN next_check_at timestamptz;

DROP INDEX payments_pending;
CREATE INDEX payments_due_for_a_check ON payments (next_check_at) WHERE status = 'pending';
