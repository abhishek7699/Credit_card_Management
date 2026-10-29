package com.creditcard.booking.exception;

import java.util.UUID;

/** Check-in/check-out precondition failed — the booking (or its room) was not in the
 *  state the operation requires (FR-006, FR-008). */
public class InvalidBookingStateException extends RuntimeException {
    public InvalidBookingStateException(UUID bookingId, String reason) {
        super("Booking " + bookingId + " cannot perform this operation: " + reason);
    }
}
