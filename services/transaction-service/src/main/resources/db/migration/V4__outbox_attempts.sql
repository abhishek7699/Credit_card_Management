-- 013 follow-up: per-row retry state for OutboxRelay. A row that reaches the attempt limit gets
-- failed_at and is no longer picked up, so it cannot block the rest of the outbox.
ALTER TABLE transaction_outbox ADD COLUMN IF NOT EXISTS attempts   INT4         NOT NULL DEFAULT 0;
ALTER TABLE transaction_outbox ADD COLUMN IF NOT EXISTS last_error VARCHAR(512);
ALTER TABLE transaction_outbox ADD COLUMN IF NOT EXISTS failed_at  TIMESTAMPTZ;
