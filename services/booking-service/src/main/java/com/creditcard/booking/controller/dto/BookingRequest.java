package com.creditcard.booking.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/** Submitted by front-desk staff on a guest's behalf (FR-004). {@code card} is
 *  forwarded to transaction-service and never persisted or logged (FR-008). */
public record BookingRequest(
        @NotNull UUID roomId,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotBlank String guestName,
        @NotNull @Valid CardDetails card
) {
    public record CardDetails(
            @NotBlank String cardNumber,
            @NotBlank String expiry,
            @NotBlank String cvv,
            @NotBlank String cardholderName
    ) {
    }
}
