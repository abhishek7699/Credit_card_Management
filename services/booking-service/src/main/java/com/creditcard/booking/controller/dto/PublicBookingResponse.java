package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.BookingStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** The guest's confirmation. {@code bookingId} is the booking reference. Nothing is
 *  charged at this point: {@code payAtCheckout} is always true for an online booking. */
public record PublicBookingResponse(
        UUID bookingId,
        BookingStatus status,
        String roomNumber,
        String roomType,
        LocalDate checkIn,
        LocalDate checkOut,
        long nights,
        BigDecimal total,
        boolean payAtCheckout
) {
}
