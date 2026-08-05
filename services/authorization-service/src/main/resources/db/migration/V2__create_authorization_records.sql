CREATE TABLE authorization_records (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id   UUID          NOT NULL,
    card_token       VARCHAR(512)  NOT NULL,
    requested_amount DECIMAL(15,4) NOT NULL,
    currency_code    VARCHAR(3)    NOT NULL,
    outcome          VARCHAR(20)   NOT NULL,
    reason_code      VARCHAR(50),
    decided_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_outcome CHECK (outcome IN ('AUTHORIZED', 'DECLINED', 'FAILED'))
);

CREATE INDEX idx_auth_records_tx_id ON authorization_records(transaction_id);
