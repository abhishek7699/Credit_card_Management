package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.RoomStatus;
import jakarta.validation.constraints.NotNull;

public record RoomStatusUpdateRequest(@NotNull RoomStatus status) {
}
