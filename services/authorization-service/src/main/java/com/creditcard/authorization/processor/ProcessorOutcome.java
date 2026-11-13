package com.creditcard.authorization.processor;

/**
 * What a {@link PaymentProcessor} operation decided. Phase 1 uses APPROVED, DECLINED, UNKNOWN,
 * RELEASED, NO_AUTHORIZATION, NO_OP and CAPTURE_REJECTED. The rest are for phases 2 and 4.
 *
 * <ul>
 *   <li>{@code NO_AUTHORIZATION}: no authorization record exists at all. The caller writes the 013 timeout marker.</li>
 *   <li>{@code NO_OP}: a record exists, but there is nothing to release (for example, it was DECLINED).</li>
 * </ul>
 */
public enum ProcessorOutcome {
    APPROVED,
    DECLINED,
    UNKNOWN,
    RELEASED,
    RELEASE_REJECTED,
    NO_AUTHORIZATION,
    NO_OP,
    CAPTURED,
    CAPTURE_REJECTED,
    TIMED_OUT_SKIPPED
}
