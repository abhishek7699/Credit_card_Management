-- Spec 014 phase 2: keep the external processor's decline code (for example, a Mastercard gateway code)
-- next to our ReasonCode, so a decline can be explained without changing the event contract.
ALTER TABLE authorization_records ADD COLUMN processor_reason_code VARCHAR(100);
