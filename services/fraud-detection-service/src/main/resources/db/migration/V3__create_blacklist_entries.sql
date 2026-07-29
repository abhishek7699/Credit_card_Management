CREATE TABLE blacklist_entries (
    card_token  VARCHAR(512) PRIMARY KEY,
    reason      VARCHAR(255),
    added_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
