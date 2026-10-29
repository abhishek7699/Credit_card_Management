package com.creditcard.booking.exception;

import java.time.LocalDate;
import java.util.UUID;

/** FR-007: at most one HELD/CONFIRMED booking per room per night — thrown when the
 *  booking_room_nights unique-constraint insert fails (research.md §1). */
public class RoomUnavailableException extends RuntimeException {
    public RoomUnavailableException(UUID roomId, LocalDate checkIn, LocalDate checkOut) {
        super("Room " + roomId + " is not available for " + checkIn + " to " + checkOut);
    }
}
