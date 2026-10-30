package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CancellationReason;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record BookingDetailResponse(
        UUID bookingId,
        UUID roomId,
        String guestName,
        LocalDate checkIn,
        LocalDate checkOut,
        BookingStatus status,
        CancellationReason cancellationReason,
        String transactionId,
        List<ChargeResponse> charges,
        BigDecimal balanceDue
) {
}
