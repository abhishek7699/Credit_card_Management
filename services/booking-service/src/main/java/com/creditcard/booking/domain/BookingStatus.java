package com.creditcard.booking.domain;

/**
 * HELD is the only payment-saga non-terminal state; CANCELLED is terminal (FR-023, no
 * reverse transitions, including for a late AUTHORIZED outcome after expiry — see
 * FR-019). CONFIRMED is no longer terminal as of layer 2b: it additionally leads to
 * CHECKED_IN (via check-in) and then CHECKED_OUT (terminal), the room-board layer's
 * own lifecycle extension — see specs/009-room-board/data-model.md.
 */
public enum BookingStatus {
    HELD,
    CONFIRMED,
    CANCELLED,
    CHECKED_IN,
    CHECKED_OUT
}
