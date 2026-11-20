package com.creditcard.booking.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** Used at checkout ({@link CheckOutRequest}) and by a guest's online booking
 *  ({@link PublicBookingRequest}); staff booking creation takes no card (FR-017). Forwarded to
 *  transaction-service for exactly one charge or one validation, never persisted or logged (FR-012). */
public record CardDetails(
        @NotBlank String cardNumber,
        @NotBlank String expiry,
        @NotBlank String cvv,
        @NotBlank String cardholderName
) {
    /** A record's default toString prints every field. Never let a card number or CVV reach a log line. */
    @Override
    public String toString() {
        return "CardDetails{cardNumber=****, expiry=****, cvv=****, cardholderName=****}";
    }
}
