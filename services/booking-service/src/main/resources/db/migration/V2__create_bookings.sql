CREATE TABLE bookings (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id              UUID NOT NULL REFERENCES rooms (id),
    guest_name           VARCHAR(200) NOT NULL,
    check_in             DATE NOT NULL,
    check_out            DATE NOT NULL,
    status               VARCHAR(20) NOT NULL,
    cancellation_reason  VARCHAR(30),
    transaction_id       VARCHAR(100),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bookings_transaction_id ON bookings (transaction_id);
CREATE INDEX idx_bookings_status_created_at ON bookings (status, created_at);
