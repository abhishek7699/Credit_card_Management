-- Request-level idempotency for InitiateTransaction (gRPC metadata "idempotency-key").
-- resource_id / response_status are NULL only while a request is in flight: the key row
-- is claimed inside the same DB transaction that creates the transaction, so any row
-- visible to another transaction is already complete.
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) NOT NULL,
    resource_id     UUID,
    response_status VARCHAR(20),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (idempotency_key)
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
