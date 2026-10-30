package com.creditcard.booking.controller.dto;

import com.creditcard.booking.domain.RoomStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** One floor's worth of rooms, ordered by position (FR-012). */
public record RoomBoardResponse(int floor, List<RoomBoardEntry> rooms) {

    /**
     * guestName/checkIn/checkOut are present only when status is OCCUPIED (FR-013).
     *
     * <p>currentBookingId/arrivingBookingId/arrivingGuestName (frontend-mvp layer,
     * additive): let any staff session check in or check out a room directly from
     * the board, without needing to have personally created or seen the underlying
     * booking earlier. currentBookingId is present only when OCCUPIED (the same
     * booking already backing guestName/checkIn/checkOut — for check-out).
     * arrivingBookingId/arrivingGuestName are present only when a CONFIRMED booking
     * exists for this room with a check-in date of today (for check-in) —
     * independent of guestName/currentBookingId, since a room can have both an
     * occupant checking out today and a different guest's booking arriving later
     * today (same-day turnover).
     */
    public record RoomBoardEntry(
            UUID roomId,
            String roomNumber,
            int position,
            RoomStatus status,
            String guestName,
            LocalDate checkIn,
            LocalDate checkOut,
            UUID currentBookingId,
            UUID arrivingBookingId,
            String arrivingGuestName
    ) {
    }
}
