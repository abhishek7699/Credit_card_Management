package com.creditcard.booking.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Submitted by front-desk staff on a guest's behalf. No card, of any kind, at this
 * step (FR-017) — the booking is confirmed immediately and the room's cost is
 * collected only at checkout, through {@link CheckOutRequest}. {@code guestName},
 * {@code guestPhone}, {@code checkIn}, and {@code checkOut} are required; every other
 * field is optional (FR-018) — {@code adults}/{@code children} default to 1/0 when
 * left null, applied in {@code BookingService#createConfirmedBooking}, not here, since
 * bean validation has no notion of "default when absent."
 */
public record BookingRequest(
        @NotNull UUID roomId,
        @NotNull LocalDate checkIn,
        @NotNull LocalDate checkOut,
        @NotBlank String guestName,
        @NotBlank String guestPhone,
        String guestEmail,
        String visitingFrom,
        @Min(1) Integer adults,
        @Min(0) Integer children,
        LocalTime expectedArrivalTime,
        LocalTime expectedDepartureTime,
        String notes
) {
}
