CREATE TABLE rooms (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    room_number  VARCHAR(20) NOT NULL UNIQUE,
    room_type    VARCHAR(50) NOT NULL,
    nightly_rate DECIMAL(10, 2) NOT NULL
);

INSERT INTO rooms (room_number, room_type, nightly_rate) VALUES
    ('101', 'STANDARD', 129.00),
    ('102', 'STANDARD', 129.00),
    ('103', 'STANDARD', 129.00),
    ('104', 'STANDARD', 129.00),
    ('105', 'STANDARD', 129.00),
    ('106', 'STANDARD', 129.00),
    ('201', 'DELUXE',   179.00),
    ('202', 'DELUXE',   179.00),
    ('203', 'DELUXE',   179.00),
    ('204', 'DELUXE',   179.00),
    ('205', 'DELUXE',   179.00),
    ('206', 'DELUXE',   179.00),
    ('301', 'SUITE',    259.00),
    ('302', 'SUITE',    259.00);
