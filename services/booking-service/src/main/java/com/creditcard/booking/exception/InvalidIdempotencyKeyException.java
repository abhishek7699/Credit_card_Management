package com.creditcard.booking.exception;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(int maxLength) {
        super("Idempotency-Key must be at most " + maxLength + " characters");
    }
}
