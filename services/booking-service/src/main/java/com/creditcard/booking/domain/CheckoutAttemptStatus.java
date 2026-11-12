package com.creditcard.booking.domain;

/**
 * PENDING -> AUTHORIZED (booking checks out, FR-010) or DECLINED/FAILED (booking
 * returns to CHECKED_IN, FR-011) or EXPIRED (the hold-expiry sweep, no outcome heard
 * in time, FR-012a). EXPIRED -> LATE_AUTHORIZED on a late AUTHORIZED outcome
 * (recorded as a payment, FR-012b) -- mirrors CancellationReason's own
 * HOLD_EXPIRED -> PAYMENT_AFTER_EXPIRY precedent exactly. EXPIRED stays EXPIRED on a
 * late DECLINED/FAILED outcome -- a true no-op, nothing to record.
 */
public enum CheckoutAttemptStatus {
    PENDING,
    AUTHORIZED,
    DECLINED,
    FAILED,
    EXPIRED,
    LATE_AUTHORIZED
}
