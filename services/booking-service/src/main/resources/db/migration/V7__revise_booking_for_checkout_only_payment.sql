-- Layer 2c rework: room cost is no longer charged at booking time, so booking
-- creation needs a richer guest record and no longer needs an in-flight
-- transaction id for the room charge itself. No column is dropped (research.md
-- §8) -- cancellation_reason and CANCELLED are kept for layer 2f's planned
-- staff-cancellation feature; only the now-impossible HELD status is removed
-- from the application-level enum, so any stray HELD row is converted below
-- rather than left holding a value the application can no longer deserialize.

ALTER TABLE bookings
    ADD COLUMN guest_phone VARCHAR(30) NOT NULL DEFAULT '',
    ADD COLUMN guest_email VARCHAR(320),
    ADD COLUMN visiting_from VARCHAR(200),
    -- INT4, not bare INT: CockroachDB's default_int_size makes a bare INT an alias
    -- for INT8/BIGINT, which then fails Hibernate's schema validation against the
    -- entity's `int` fields (expects a 4-byte INTEGER) -- same pitfall V5 already
    -- documented for rooms.floor/position.
    ADD COLUMN adults INT4 NOT NULL DEFAULT 1 CHECK (adults >= 1),
    ADD COLUMN children INT4 NOT NULL DEFAULT 0 CHECK (children >= 0),
    ADD COLUMN expected_arrival_time TIME,
    ADD COLUMN expected_departure_time TIME,
    ADD COLUMN notes VARCHAR(2000);

ALTER TABLE bookings RENAME COLUMN transaction_id TO legacy_room_payment_transaction_id;

-- Free the room-night slots any never-confirmed hold was reserving, then
-- convert the row itself so no booking is left carrying a status value the
-- (now smaller) application-level BookingStatus enum no longer recognizes.
DELETE FROM booking_room_nights
WHERE booking_id IN (SELECT id FROM bookings WHERE status = 'HELD');

UPDATE bookings
SET status = 'CANCELLED', cancellation_reason = 'MIGRATED_UNPAID_HOLD'
WHERE status = 'HELD';
