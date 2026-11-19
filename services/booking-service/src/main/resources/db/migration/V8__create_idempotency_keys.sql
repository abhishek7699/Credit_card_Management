-- Request-level idempotency for POST /api/v1/bookings (Idempotency-Key header).
-- resource_id / response_status are NULL only while a request is in flight: the key row
-- is claimed inside the same DB transaction that creates the booking, so any row visible
-- to another transaction is already complete.
CREATE TABLE idempotency_keys (
    idempotency_key VARCHAR(255) NOT NULL,
    resource_id     UUID,
    response_status VARCHAR(20),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (idempotency_key)
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
