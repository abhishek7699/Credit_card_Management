package com.creditcard.booking.repository;

import com.creditcard.booking.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface RoomRepository extends JpaRepository<Room, java.util.UUID> {

    /**
     * Rooms with no booking_room_nights row for any date in [checkIn, checkOut) —
     * the full availability query (FR-002, data-model.md).
     */
    @Query("""
        SELECT r FROM Room r
        WHERE r.id NOT IN (
            SELECT brn.roomId FROM BookingRoomNight brn
            WHERE brn.stayDate >= :checkIn AND brn.stayDate < :checkOut
        )
        ORDER BY r.roomNumber
        """)
    List<Room> findAvailable(@Param("checkIn") LocalDate checkIn, @Param("checkOut") LocalDate checkOut);
}
