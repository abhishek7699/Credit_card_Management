package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.CardAccount;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Decides approval for authorization-service (spec 014, contracts/payment-processor.md).
 * Called only from the service layer. Duplicate checks and the timeout marker stay in the service.
 *
 * <p>{@code lockedAccount} arguments are card rows the service has already locked (013). They are null
 * when no card row exists.
 */
public interface PaymentProcessor {

    /**
     * Decide one authorization. Approval reserves the amount. The authorization record is written by
     * the caller, not here.
     */
    ProcessorDecision authorize(AuthorizeRequest request);

    /** Look up what happened to a transaction. Phase 1 simulator: always UNKNOWN. */
    ProcessorDecision lookup(UUID transactionId);

    /** Capture an authorized transaction. Phase 1 simulator: always CAPTURE_REJECTED (no capture). */
    ProcessorDecision capture(UUID transactionId, BigDecimal amount, String currencyCode);

    /**
     * Give back a hold placed by {@link #authorize} before capture. Returns {@code NO_AUTHORIZATION} when no
     * authorization record exists (the caller writes the timeout marker), and {@code NO_OP} when a record
     * exists but nothing is held.
     */
    ProcessorDecision releaseHold(UUID transactionId, CardAccount lockedAccount);
}
