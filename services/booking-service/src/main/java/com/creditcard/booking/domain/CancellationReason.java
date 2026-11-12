package com.creditcard.booking.domain;

/**
 * Set only when {@link BookingStatus#CANCELLED}. PAYMENT_AFTER_EXPIRY is distinct from
 * HOLD_EXPIRED: it marks a booking that was already HOLD_EXPIRED when a late AUTHORIZED
 * outcome subsequently arrived for it (FR-019) — the cancellation itself never reverses,
 * only the reason is updated, plus a logged warning for manual refund follow-up.
 *
 * <p>Nothing in this layer's own code produces PAYMENT_DECLINED, PAYMENT_FAILED,
 * HOLD_EXPIRED, or PAYMENT_AFTER_EXPIRY any more as of the 2026-10-31 rework — the
 * booking-time payment saga that was their only source is removed. They're kept,
 * unchanged, for any pre-revision historical row and for layer 2f's planned
 * staff-cancellation feature to extend (specs/011-charges/research.md §8).
 *
 * <p>MIGRATED_UNPAID_HOLD is set exactly once per row, only by migration
 * V7__revise_booking_for_checkout_only_payment.sql, converting any stray HELD booking
 * left over from before this rework (HELD no longer exists in {@link BookingStatus}).
 */
public enum CancellationReason {
    PAYMENT_DECLINED,
    PAYMENT_FAILED,
    HOLD_EXPIRED,
    PAYMENT_AFTER_EXPIRY,
    MIGRATED_UNPAID_HOLD
}
