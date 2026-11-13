-- 013: failure reason for FAILED transactions (only TIMEOUT is written, by the sweep).
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(32);

-- Transactional outbox: final outcomes and transaction.timed_out are written here in the
-- same DB transaction as the status change, then relayed to Kafka by OutboxRelay.
-- event_id is stable (derived from transactionId and final status), so a relay that
-- republishes a row produces a duplicate that consumers drop via processed_events.
CREATE TABLE IF NOT EXISTS transaction_outbox (
    event_id    UUID          NOT NULL,
    topic       VARCHAR(128)  NOT NULL,
    message_key VARCHAR(64)   NOT NULL,
    event_type  VARCHAR(64)   NOT NULL,
    payload     VARCHAR(4096) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ,
    PRIMARY KEY (event_id)
);

CREATE INDEX IF NOT EXISTS idx_transaction_outbox_unsent ON transaction_outbox (sent_at, created_at);
