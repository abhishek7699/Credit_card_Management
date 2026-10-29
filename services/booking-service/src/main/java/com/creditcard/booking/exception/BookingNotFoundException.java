package com.creditcard.booking.exception;

import java.util.UUID;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(UUID bookingId) {
        super("No booking found with id: " + bookingId);
    }
}
