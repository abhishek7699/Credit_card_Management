package com.creditcard.authorization.processor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Decides approval for authorization-service (spec 014, contracts/payment-processor.md).
 *
 * <p>Processor-neutral: no method takes a card row. Each implementation does its own lookups and
 * locking. The Mastercard path never touches {@code card_accounts}.
 *
 * <p>Contract for every implementation:
 * <ul>
 *   <li>{@link #authorize} and {@link #releaseHold} for the same transaction are serialized by the
 *       implementation. A release and an authorization for one transaction cannot interleave.</li>
 *   <li>{@link #authorize} returns {@code TIMED_OUT_SKIPPED} without deciding when a timeout marker
 *       exists for the transaction (013).</li>
 * </ul>
 */
public interface PaymentProcessor {

    /**
     * Decide one authorization. Approval reserves the amount. The authorization record is written by
     * the caller, not here.
     */
    ProcessorDecision authorize(AuthorizeRequest request);

    /** Look up what happened to a transaction. Simulator: always UNKNOWN. */
    ProcessorDecision lookup(UUID transactionId);

    /** Capture an authorized transaction. Simulator: always CAPTURE_REJECTED. Mastercard: phase 4. */
    ProcessorDecision capture(UUID transactionId, BigDecimal amount, String currencyCode);

    /**
     * Give back a hold placed by {@link #authorize}, before capture.
     * {@code NO_AUTHORIZATION} when no authorization exists (the caller writes the timeout marker).
     * {@code NO_OP} when a record exists but nothing is held.
     */
    ProcessorDecision releaseHold(UUID transactionId, String paymentToken);
}
