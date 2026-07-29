CREATE TABLE fraud_rule_configs (
    rule_type   VARCHAR(50) PRIMARY KEY,
    enabled     BOOLEAN     NOT NULL DEFAULT true,
    parameters  TEXT        NOT NULL DEFAULT '{}',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO fraud_rule_configs (rule_type, enabled, parameters) VALUES
    ('VELOCITY_EXCEEDED', true, '{"maxTransactions": 5, "windowMinutes": 10}'),
    ('AMOUNT_ANOMALY',    true, '{"multiplier": 3.0, "lookbackDays": 30, "absoluteCeiling": 10000.00}'),
    ('BLACKLISTED_CARD',  true, '{}');
