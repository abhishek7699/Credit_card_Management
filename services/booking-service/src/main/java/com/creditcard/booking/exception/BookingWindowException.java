package com.creditcard.booking.exception;

/** A guest-made booking (public front office) falls outside the online booking rules:
 *  stay longer than the maximum, check-in in the past, or too far ahead. */
public class BookingWindowException extends RuntimeException {
    public BookingWindowException(String message) {
        super(message);
    }
}
