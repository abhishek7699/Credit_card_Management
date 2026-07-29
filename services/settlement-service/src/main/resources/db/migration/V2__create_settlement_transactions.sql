CREATE TABLE settlement_transactions (
    transaction_id    UUID         NOT NULL,
    merchant_id       VARCHAR(100) NOT NULL,
    card_token        VARCHAR(512) NOT NULL,
    masked_card_number VARCHAR(19) NOT NULL,
    amount            DECIMAL(15,2) NOT NULL,
    currency_code     VARCHAR(3)   NOT NULL DEFAULT 'USD',
    status            VARCHAR(20)  NOT NULL DEFAULT 'AUTHORIZED',
    batch_id          VARCHAR(100),
    settlement_fee    DECIMAL(15,2),
    net_amount        DECIMAL(15,2),
    authorized_at     TIMESTAMPTZ  NOT NULL,
    settled_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    PRIMARY KEY (transaction_id)
);

CREATE INDEX idx_settlement_transactions_status
    ON settlement_transactions (status);

CREATE INDEX idx_settlement_transactions_merchant_batch
    ON settlement_transactions (merchant_id, batch_id);
