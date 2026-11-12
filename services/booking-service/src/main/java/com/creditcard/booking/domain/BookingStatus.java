package com.creditcard.booking.domain;

/**
 * A booking is CONFIRMED the instant it's created (layer 2c rework — no more HELD
 * awaiting a booking-time payment outcome; see specs/011-charges/research.md §8).
 * CONFIRMED additionally leads to CHECKED_IN (via check-in) and then CHECKED_OUT
 * (terminal), the room-board layer's own lifecycle extension — see
 * specs/009-room-board/data-model.md.
 *
 * <p>CANCELLED is kept, terminal, no reverse transitions — but this layer's own code
 * never produces it any more (its only paths, a declined/failed booking-time charge
 * and hold expiry, are both removed). It stays in the enum because layer 2f's planned
 * staff-cancellation feature will reach it; the only way to reach it today is a
 * pre-revision historical row, or the V7 migration's one-time HELD conversion (see
 * CancellationReason#MIGRATED_UNPAID_HOLD).
 *
 * <p>CHECKOUT_PENDING (layer 2c): reached only from CHECKED_IN, only when the
 * booking's balance due is greater than zero at checkout — left either to
 * CHECKED_OUT (the checkout payment attempt is approved) or back to CHECKED_IN
 * (declined, failed, or swept as expired) — see specs/011-charges/data-model.md. A
 * zero-balance checkout never passes through this state at all.
 */
public enum BookingStatus {
    CONFIRMED,
    CANCELLED,
    CHECKED_IN,
    CHECKOUT_PENDING,
    CHECKED_OUT
}
