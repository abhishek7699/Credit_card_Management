CREATE TABLE transaction_records (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID          NOT NULL UNIQUE,
    card_token      VARCHAR(512)  NOT NULL,
    amount          DECIMAL(15,4) NOT NULL,
    currency_code   CHAR(3)       NOT NULL,
    occurred_at     TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_tx_records_card_time ON transaction_records(card_token, occurred_at);
