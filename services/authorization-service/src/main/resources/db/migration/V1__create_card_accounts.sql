CREATE TABLE card_accounts (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    card_token       VARCHAR(512)  NOT NULL UNIQUE,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    credit_limit     DECIMAL(15,4) NOT NULL,
    available_credit DECIMAL(15,4) NOT NULL,
    currency_code    CHAR(3)       NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_card_status         CHECK (status IN ('ACTIVE', 'BLOCKED', 'EXPIRED')),
    CONSTRAINT chk_credit_limit_pos    CHECK (credit_limit > 0),
    CONSTRAINT chk_available_credit_nn CHECK (available_credit >= 0)
);

CREATE INDEX idx_card_accounts_token ON card_accounts(card_token);
