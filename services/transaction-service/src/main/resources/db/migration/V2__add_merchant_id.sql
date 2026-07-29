ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS merchant_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_transactions_merchant_id ON transactions (merchant_id);
