CREATE TABLE processed_events (
    event_key    VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    PRIMARY KEY (event_key)
);
