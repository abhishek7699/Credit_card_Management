package com.creditcard.booking.exception;

import com.creditcard.booking.domain.RoomStatus;

import java.util.UUID;

/** PATCH /api/v1/rooms/{id}/status requested a transition outside the allowed set
 *  (FR-010, FR-011) — the room's status is left unchanged. */
public class InvalidRoomStatusTransitionException extends RuntimeException {
    public InvalidRoomStatusTransitionException(UUID roomId, RoomStatus from, RoomStatus to) {
        super("Room " + roomId + " cannot transition from " + from + " to " + to);
    }
}
