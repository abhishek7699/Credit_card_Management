CREATE TABLE notification_records (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id         VARCHAR(100) NOT NULL,
    event_type       VARCHAR(50)  NOT NULL,
    channel          VARCHAR(10)  NOT NULL,
    recipient_masked VARCHAR(100) NOT NULL,
    content          TEXT         NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    failure_reason   TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at          TIMESTAMPTZ
);

CREATE INDEX idx_notif_records_event_id ON notification_records (event_id);
CREATE INDEX idx_notif_records_status   ON notification_records (status);
