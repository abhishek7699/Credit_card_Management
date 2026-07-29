CREATE TABLE IF NOT EXISTS transactions (
    id                 UUID          NOT NULL DEFAULT gen_random_uuid(),
    card_token         VARCHAR(512)  NOT NULL,
    masked_card_number VARCHAR(19)   NOT NULL,
    cardholder_name    VARCHAR(255)  NOT NULL,
    amount             DECIMAL(15,2) NOT NULL CHECK (amount > 0),
    currency_code      CHAR(3)       NOT NULL,
    status             VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_transactions_status ON transactions (status);

CREATE TABLE IF NOT EXISTS processed_events (
    event_key    VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_key)
);
