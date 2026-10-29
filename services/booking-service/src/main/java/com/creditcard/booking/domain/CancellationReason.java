package com.creditcard.booking.domain;

/**
 * Set only when {@link BookingStatus#CANCELLED}. PAYMENT_AFTER_EXPIRY is distinct from
 * HOLD_EXPIRED: it marks a booking that was already HOLD_EXPIRED when a late AUTHORIZED
 * outcome subsequently arrived for it (FR-019) — the cancellation itself never reverses,
 * only the reason is updated, plus a logged warning for manual refund follow-up.
 */
public enum CancellationReason {
    PAYMENT_DECLINED,
    PAYMENT_FAILED,
    HOLD_EXPIRED,
    PAYMENT_AFTER_EXPIRY
}
