-- Additive only: no column is dropped, no table is recreated, no existing room's
-- id changes. Adds the room-board layout (floor/position) and operational status
-- (layer 2b) on top of the existing 14 seeded rooms, then completes the 3-floor x
-- 6-room layout (18 total) with 4 new rows.

-- INT4, not bare INT/INTEGER: CockroachDB's default_int_size makes a bare INT an
-- 8-byte INT8 column, which Hibernate's schema validation rejects against this
-- entity's `Integer` fields (expects a 4-byte INTEGER). INT4 is CockroachDB's
-- explicit 32-bit alias, matching what Hibernate actually validates against.
ALTER TABLE rooms ADD COLUMN floor INT4;
ALTER TABLE rooms ADD COLUMN position INT4;

-- Existing room_number already encodes this layout: hundreds digit = floor,
-- ones digit = position within that floor.
UPDATE rooms SET floor = 1, position = 1 WHERE room_number = '101';
UPDATE rooms SET floor = 1, position = 2 WHERE room_number = '102';
UPDATE rooms SET floor = 1, position = 3 WHERE room_number = '103';
UPDATE rooms SET floor = 1, position = 4 WHERE room_number = '104';
UPDATE rooms SET floor = 1, position = 5 WHERE room_number = '105';
UPDATE rooms SET floor = 1, position = 6 WHERE room_number = '106';
UPDATE rooms SET floor = 2, position = 1 WHERE room_number = '201';
UPDATE rooms SET floor = 2, position = 2 WHERE room_number = '202';
UPDATE rooms SET floor = 2, position = 3 WHERE room_number = '203';
UPDATE rooms SET floor = 2, position = 4 WHERE room_number = '204';
UPDATE rooms SET floor = 2, position = 5 WHERE room_number = '205';
UPDATE rooms SET floor = 2, position = 6 WHERE room_number = '206';
UPDATE rooms SET floor = 3, position = 1 WHERE room_number = '301';
UPDATE rooms SET floor = 3, position = 2 WHERE room_number = '302';

-- Complete floor 3 (4 new rooms) to reach the 3x6 = 18-room layout.
INSERT INTO rooms (room_number, room_type, nightly_rate, floor, position) VALUES
    ('303', 'SUITE', 259.00, 3, 3),
    ('304', 'SUITE', 259.00, 3, 4),
    ('305', 'SUITE', 259.00, 3, 5),
    ('306', 'SUITE', 259.00, 3, 6);

ALTER TABLE rooms ALTER COLUMN floor SET NOT NULL;
ALTER TABLE rooms ALTER COLUMN position SET NOT NULL;
ALTER TABLE rooms ADD CONSTRAINT uq_rooms_floor_position UNIQUE (floor, position);

-- Operational status (layer 2b): every room starts VACANT_CLEAN, including the
-- 14 pre-existing rows, via the column default.
ALTER TABLE rooms ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'VACANT_CLEAN';
