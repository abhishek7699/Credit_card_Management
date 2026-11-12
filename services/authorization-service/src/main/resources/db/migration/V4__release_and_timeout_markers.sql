-- 013: a deduction can be RELEASED after its transaction times out.
ALTER TABLE authorization_records DROP CONSTRAINT chk_outcome;
ALTER TABLE authorization_records ADD CONSTRAINT chk_outcome
    CHECK (outcome IN ('AUTHORIZED', 'DECLINED', 'FAILED', 'RELEASED'));

-- 013: a transaction timed out before authorization ran; a late transaction.initiated skips authorization.
CREATE TABLE timeout_markers (
    transaction_id UUID        PRIMARY KEY,
    marked_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
