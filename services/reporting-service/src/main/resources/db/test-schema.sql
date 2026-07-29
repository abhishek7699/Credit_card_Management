-- Test schema for Testcontainers integration tests.
-- NOT run in production — settlement-service owns and migrates these tables.

CREATE TABLE IF NOT EXISTS settlement_transactions (
    transaction_id  VARCHAR(100) PRIMARY KEY,
    merchant_id     VARCHAR(100) NOT NULL,
    card_token      VARCHAR(512) NOT NULL,
    masked_card_number VARCHAR(19) NOT NULL,
    amount          DECIMAL(15,2) NOT NULL,
    currency_code   CHAR(3) NOT NULL DEFAULT 'USD',
    status          VARCHAR(20) NOT NULL DEFAULT 'AUTHORIZED',
    batch_id        VARCHAR(100),
    settlement_fee  DECIMAL(15,2),
    net_amount      DECIMAL(15,2),
    authorized_at   TIMESTAMPTZ NOT NULL,
    settled_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_st_status ON settlement_transactions(status);
CREATE INDEX IF NOT EXISTS idx_st_merchant_batch ON settlement_transactions(merchant_id, batch_id);
CREATE INDEX IF NOT EXISTS idx_st_authorized_at ON settlement_transactions(authorized_at);

CREATE TABLE IF NOT EXISTS merchant_settlements (
    id                      VARCHAR(100) PRIMARY KEY,
    batch_id                VARCHAR(100) NOT NULL,
    merchant_id             VARCHAR(100) NOT NULL,
    transaction_count       INT NOT NULL DEFAULT 0,
    gross_amount            DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_interchange_fee   DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    net_settlement_amount   DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    currency_code           CHAR(3) NOT NULL DEFAULT 'USD',
    settlement_date         DATE NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(batch_id, merchant_id)
);

CREATE INDEX IF NOT EXISTS idx_ms_merchant_batch ON merchant_settlements(merchant_id, batch_id);
CREATE INDEX IF NOT EXISTS idx_ms_settlement_date ON merchant_settlements(settlement_date DESC);

CREATE TABLE IF NOT EXISTS settlement_batches (
    batch_id                        VARCHAR(100) PRIMARY KEY,
    triggered_by                    VARCHAR(20) NOT NULL,
    status                          VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    total_transactions_processed    INT NOT NULL DEFAULT 0,
    total_gross_amount              DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_interchange_fees          DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_net_amount                DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    started_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at                    TIMESTAMPTZ,
    failure_reason                  TEXT
);

CREATE INDEX IF NOT EXISTS idx_sb_status ON settlement_batches(status);
CREATE INDEX IF NOT EXISTS idx_sb_started_at ON settlement_batches(started_at DESC);
