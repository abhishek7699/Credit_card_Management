package com.creditcard.booking.exception;

/** The guest's card did not validate. The booking is rejected and nothing is stored. The message is shown to the guest. */
public class CardNotVerifiedException extends RuntimeException {
    public CardNotVerifiedException() {
        super("We couldn't verify your card. Please check the card details or use a different card.");
    }
}
