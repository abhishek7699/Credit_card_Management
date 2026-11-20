-- Front office (guest-facing booking). Additive only: no column is dropped and no
-- existing row changes.
--
-- 1. room_inclusions: what a guest gets with a room (e.g. Wi-Fi, Breakfast), one row
--    per item. Seeded for the existing 18 rooms: every room gets Wi-Fi, DELUXE and
--    SUITE rooms also get Breakfast.
-- 2. bookings.source: where a booking came from. STAFF for every existing row (they
--    were all created by front-desk staff); ONLINE for bookings made by guests.

CREATE TABLE room_inclusions (
    room_id UUID        NOT NULL REFERENCES rooms (id),
    item    VARCHAR(50) NOT NULL,
    PRIMARY KEY (room_id, item)
);

INSERT INTO room_inclusions (room_id, item)
SELECT id, 'Wi-Fi' FROM rooms;

INSERT INTO room_inclusions (room_id, item)
SELECT id, 'Breakfast' FROM rooms WHERE room_type IN ('DELUXE', 'SUITE');

ALTER TABLE bookings
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'STAFF' CHECK (source IN ('STAFF', 'ONLINE'));
