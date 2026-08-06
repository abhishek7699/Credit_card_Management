CREATE TABLE settlement_batches (
    batch_id                    VARCHAR(100)  NOT NULL,
    triggered_by                VARCHAR(20)   NOT NULL,
    status                      VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    total_transactions_processed INT4         NOT NULL DEFAULT 0,
    total_gross_amount          DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_interchange_fees      DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    total_net_amount            DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    started_at                  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at                TIMESTAMPTZ,
    failure_reason              TEXT,

    PRIMARY KEY (batch_id)
);

CREATE INDEX idx_settlement_batches_status
    ON settlement_batches (status);

CREATE INDEX idx_settlement_batches_started_at
    ON settlement_batches (started_at DESC);
