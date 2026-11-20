package com.creditcard.booking.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A guest's online booking: the same booking fields staff use, plus a card. The card is only
 * <em>checked</em> (Mastercard Account Validation, no charge) to guarantee the pay-later booking; it is
 * never stored. Staff bookings keep using {@link BookingRequest}, with no card.
 */
public record PublicBookingRequest(
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
        String notes,
        @NotNull @Valid CardDetails card
) {
    public BookingRequest toBookingRequest() {
        return new BookingRequest(roomId, checkIn, checkOut, guestName, guestPhone, guestEmail, visitingFrom,
                adults, children, expectedArrivalTime, expectedDepartureTime, notes);
    }
}
