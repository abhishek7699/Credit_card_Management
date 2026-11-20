package com.creditcard.booking.exception;

/** The card check could not be completed (Mastercard or transaction-service unreachable). Fail closed: retry later. */
public class CardValidationUnavailableException extends RuntimeException {
    public CardValidationUnavailableException() {
        super("We couldn't check your card right now. Please try again in a moment.");
    }
}
