package com.creditcard.booking.service;

import com.creditcard.booking.domain.Room;
import com.creditcard.booking.exception.InvalidDateRangeException;
import com.creditcard.booking.repository.RoomRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class RoomAvailabilityService {

    private final RoomRepository roomRepository;

    public RoomAvailabilityService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    /** FR-002: only rooms with no HELD/CONFIRMED booking overlapping [checkIn, checkOut). */
    public List<Room> findAvailable(LocalDate checkIn, LocalDate checkOut) {
        if (!checkOut.isAfter(checkIn)) {
            throw new InvalidDateRangeException(checkIn, checkOut);
        }
        return roomRepository.findAvailable(checkIn, checkOut);
    }
}
