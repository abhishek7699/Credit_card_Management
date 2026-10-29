package com.creditcard.booking.repository;

import com.creditcard.booking.domain.BookingRoomNight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BookingRoomNightRepository extends JpaRepository<BookingRoomNight, UUID> {

    /** Frees a cancelled booking's nights — always called in the same transaction as
     *  the booking's own status update (Key Design Decision #1, plan.md). */
    void deleteByBookingId(UUID bookingId);
}
