CREATE TABLE merchant_settlements (
    id                      UUID          NOT NULL DEFAULT gen_random_uuid(),
    batch_id                VARCHAR(100)  NOT NULL,
    merchant_id             VARCHAR(100)  NOT NULL,
    transaction_count       INT4          NOT NULL,
    gross_amount            DECIMAL(15,2) NOT NULL,
    total_interchange_fee   DECIMAL(15,2) NOT NULL,
    net_settlement_amount   DECIMAL(15,2) NOT NULL,
    currency_code           VARCHAR(3)    NOT NULL DEFAULT 'USD',
    settlement_date         DATE          NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    PRIMARY KEY (id),
    UNIQUE (batch_id, merchant_id)
);

CREATE INDEX idx_merchant_settlements_merchant_batch
    ON merchant_settlements (merchant_id, batch_id);

CREATE INDEX idx_merchant_settlements_date
    ON merchant_settlements (settlement_date DESC);
