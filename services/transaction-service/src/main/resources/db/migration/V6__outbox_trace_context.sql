-- Distributed tracing: the trace an outbox row belongs to, captured when the row is written.
-- OutboxPublisher restores it when it sends the row, so a relayed event keeps the trace of the
-- request that caused it. Rows written before this migration have no trace (NULL) and are sent
-- as before.
ALTER TABLE transaction_outbox ADD COLUMN IF NOT EXISTS trace_id      VARCHAR(32);
ALTER TABLE transaction_outbox ADD COLUMN IF NOT EXISTS span_id       VARCHAR(16);
ALTER TABLE transaction_outbox ADD COLUMN IF NOT EXISTS trace_sampled BOOLEAN;
