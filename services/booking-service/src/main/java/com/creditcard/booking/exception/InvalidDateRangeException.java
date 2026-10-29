package com.creditcard.booking.exception;

import java.time.LocalDate;

/** FR-003: checkOut must be strictly after checkIn. */
public class InvalidDateRangeException extends RuntimeException {
    public InvalidDateRangeException(LocalDate checkIn, LocalDate checkOut) {
        super("checkOut (" + checkOut + ") must be strictly after checkIn (" + checkIn + ")");
    }
}
