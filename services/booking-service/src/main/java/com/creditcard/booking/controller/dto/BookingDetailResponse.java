package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CancellationReason;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** {@code transactionId} is deliberately absent — its only remaining role is an
 *  internal legacy-payment marker (data-model.md), not something staff need to see.
 *  {@code cancellationReason} stays (always {@code null} for a booking this layer's
 *  own code creates) since layer 2f's planned staff-cancellation feature will give it
 *  a value again. */
public record BookingDetailResponse(
        UUID bookingId,
        UUID roomId,
        BookingStatus status,
        CancellationReason cancellationReason,
        String guestName,
        String guestPhone,
        String guestEmail,
        String visitingFrom,
        int adults,
        int children,
        LocalDate checkIn,
        LocalDate checkOut,
        LocalTime expectedArrivalTime,
        LocalTime expectedDepartureTime,
        String notes,
        List<ChargeResponse> charges,
        BigDecimal balanceDue
) {
}
