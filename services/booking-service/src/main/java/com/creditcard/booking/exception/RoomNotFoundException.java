package com.creditcard.booking.exception;

import java.util.UUID;

public class RoomNotFoundException extends RuntimeException {
    public RoomNotFoundException(UUID roomId) {
        super("No room found with id: " + roomId);
    }
}
