-- Pay-later guest bookings are guaranteed by a no-charge Mastercard Account Validation check.
-- We keep ONLY the outcome: the verdict, Mastercard's referenceId, and the card's last 4 digits.
-- The card number and CVC are never stored. Additive and nullable: staff bookings and every
-- existing row have no validation, so they are unchanged.
ALTER TABLE bookings
    ADD COLUMN card_validation_status       VARCHAR(20) CHECK (card_validation_status IN ('MATCHED', 'NOT_MATCHED')),
    ADD COLUMN card_validation_reference_id VARCHAR(64),
    ADD COLUMN card_last4                   VARCHAR(4);
