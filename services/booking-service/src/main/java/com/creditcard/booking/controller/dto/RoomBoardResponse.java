package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.RoomStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One floor's worth of rooms, ordered by position (FR-012). */
public record RoomBoardResponse(int floor, List<RoomBoardEntry> rooms) {

    /** guestName/checkIn/checkOut are present only when status is OCCUPIED (FR-013). */
    public record RoomBoardEntry(
            UUID roomId,
            String roomNumber,
            int position,
            RoomStatus status,
            String guestName,
            LocalDate checkIn,
            LocalDate checkOut
    ) {
    }
}
