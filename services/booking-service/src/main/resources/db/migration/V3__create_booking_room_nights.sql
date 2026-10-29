-- The sole enforcement mechanism for "at most one HELD or CONFIRMED booking per
-- room per night" (FR-007, data-model.md). One row per night of a stay; a row's
-- presence means that room is taken for that date. Inserted when a booking becomes
-- HELD, deleted (in the same transaction as the status update) when it is cancelled.
CREATE TABLE booking_room_nights (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id  UUID NOT NULL REFERENCES bookings (id),
    room_id     UUID NOT NULL REFERENCES rooms (id),
    stay_date   DATE NOT NULL,
    CONSTRAINT uq_booking_room_nights_room_date UNIQUE (room_id, stay_date)
);

CREATE INDEX idx_booking_room_nights_booking_id ON booking_room_nights (booking_id);
