package com.creditcard.booking.domain;

/**
 * HELD is the only payment-saga non-terminal state; CANCELLED is terminal (FR-023, no
 * reverse transitions, including for a late AUTHORIZED outcome after expiry — see
 * FR-019). CONFIRMED is no longer terminal as of layer 2b: it additionally leads to
 * CHECKED_IN (via check-in) and then CHECKED_OUT (terminal), the room-board layer's
 * own lifecycle extension — see specs/009-room-board/data-model.md.
 *
 * <p>CHECKOUT_PENDING (layer 2c): reached only from CHECKED_IN, only when the
 * booking's balance due is greater than zero at checkout — left either to
 * CHECKED_OUT (the checkout payment attempt is approved) or back to CHECKED_IN
 * (declined, failed, or swept as expired) — see specs/011-charges/data-model.md. A
 * zero-balance checkout never passes through this state at all.
 */
public enum BookingStatus {
    HELD,
    CONFIRMED,
    CANCELLED,
    CHECKED_IN,
    CHECKOUT_PENDING,
    CHECKED_OUT
}
