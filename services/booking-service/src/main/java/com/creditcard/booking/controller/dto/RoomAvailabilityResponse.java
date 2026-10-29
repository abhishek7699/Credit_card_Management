package com.creditcard.booking.controller.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record RoomAvailabilityResponse(UUID roomId, String roomNumber, String roomType, BigDecimal nightlyRate) {
}
