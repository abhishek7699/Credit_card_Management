package com.creditcard.booking.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** Used only at checkout ({@link CheckOutRequest}) now — booking creation no longer
 *  takes a card at all (FR-017). Forwarded to transaction-service for exactly one
 *  charge and never persisted or logged (FR-012). */
public record CardDetails(
        @NotBlank String cardNumber,
        @NotBlank String expiry,
        @NotBlank String cvv,
        @NotBlank String cardholderName
) {
}
