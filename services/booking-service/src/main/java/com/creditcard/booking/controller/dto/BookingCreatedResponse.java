package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.BookingStatus;

import java.util.UUID;

public record BookingCreatedResponse(UUID bookingId, BookingStatus status) {
}
