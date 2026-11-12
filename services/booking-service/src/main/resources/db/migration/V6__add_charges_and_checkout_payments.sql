-- Additive only: no existing column, table, or row is changed or dropped.
-- Layer 2c (charges): per-stay charges, the checkout payment saga's own attempt
-- history, and the one historical fact balance calculation needs -- the amount
-- actually charged at booking time (plan.md Key Design Decision #1).

ALTER TABLE bookings ADD COLUMN room_charge_amount DECIMAL(10, 2);

CREATE TABLE charges (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID NOT NULL REFERENCES bookings (id),
    type        VARCHAR(20) NOT NULL,
    description VARCHAR(500) NOT NULL,
    amount      DECIMAL(10, 2) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_charges_booking_id ON charges (booking_id);

-- One row per checkout payment attempt (plan.md Key Design Decision #2 — a
-- booking can accumulate more than one over its lifetime: declined or timed out,
-- then retried). transaction_id is nullable: it stays null if the gRPC call
-- itself fails outright, mirroring initiatePayment's existing handling for the
-- original room charge.
CREATE TABLE checkout_payment_attempts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id     UUID NOT NULL REFERENCES bookings (id),
    transaction_id VARCHAR(100),
    amount         DECIMAL(10, 2) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_checkout_payment_attempts_booking_id ON checkout_payment_attempts (booking_id);
CREATE INDEX idx_checkout_payment_attempts_transaction_id ON checkout_payment_attempts (transaction_id);
-- The sweep's own age-based query (research.md §4): find PENDING attempts older
-- than the hold-expiry window, same shape as bookings' own status+created_at index.
CREATE INDEX idx_checkout_payment_attempts_status_created_at ON checkout_payment_attempts (status, created_at);
