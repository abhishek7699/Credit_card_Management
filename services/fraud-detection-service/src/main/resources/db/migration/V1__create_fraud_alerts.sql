CREATE TABLE fraud_alerts (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID          NOT NULL,
    card_token      VARCHAR(512)  NOT NULL,
    amount          DECIMAL(15,4) NOT NULL,
    currency_code   VARCHAR(3)    NOT NULL,
    triggered_rules TEXT          NOT NULL,
    detected_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_fraud_alerts_tx_id ON fraud_alerts(transaction_id);
CREATE INDEX idx_fraud_alerts_card  ON fraud_alerts(card_token);
