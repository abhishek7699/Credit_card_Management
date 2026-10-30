package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.RoomStatus;

import java.util.UUID;

public record RoomStatusResponse(UUID roomId, RoomStatus status) {
}
