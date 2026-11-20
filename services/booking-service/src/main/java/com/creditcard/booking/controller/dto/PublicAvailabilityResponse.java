package com.creditcard.booking.controller.dto;

import java.time.LocalDate;
import java.util.List;

public record PublicAvailabilityResponse(LocalDate checkIn, LocalDate checkOut, long nights, List<PublicRoomOffer> rooms) {
}
