-- Transaction outcomes that arrived before their checkout attempt was linked to a transactionId.
-- An attempt is saved PENDING before the InitiateTransaction call and linked from its reply, so an
-- outcome can arrive first. A row is kept until it is applied and is never deleted while unmatched.
CREATE TABLE unmatched_payment_outcomes (
    transaction_id VARCHAR(64) NOT NULL,
    event_id       VARCHAR(64) NOT NULL,
    outcome        VARCHAR(20) NOT NULL,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (transaction_id)
);

CREATE INDEX idx_unmatched_payment_outcomes_received_at ON unmatched_payment_outcomes (received_at);
