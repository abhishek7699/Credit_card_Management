CREATE TABLE cardholder_contacts (
    card_token                        VARCHAR(512) PRIMARY KEY,
    masked_card_number                VARCHAR(19)  NOT NULL,
    email                             VARCHAR(320),
    phone_number                      VARCHAR(20),
    transaction_notifications_enabled BOOLEAN      NOT NULL DEFAULT TRUE,
    registered_at                     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
