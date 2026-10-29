package com.creditcard.booking.domain;

/**
 * HELD is the only non-terminal state. CONFIRMED and CANCELLED are terminal — once a
 * booking reaches either, no further transition is ever applied (FR-023, no reverse
 * transitions, including for a late AUTHORIZED outcome after expiry — see FR-019).
 */
public enum BookingStatus {
    HELD,
    CONFIRMED,
    CANCELLED
}
