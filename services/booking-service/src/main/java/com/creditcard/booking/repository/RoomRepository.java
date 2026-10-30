package com.creditcard.booking.repository;

import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    /**
     * Rooms with no booking_room_nights row for any date in [checkIn, checkOut),
     * excluding any room currently in MAINTENANCE regardless of that room's booking
     * calendar (FR-002, FR-014, data-model.md).
     */
    @Query("""
        SELECT r FROM Room r
        WHERE r.status <> com.creditcard.booking.domain.RoomStatus.MAINTENANCE
        AND r.id NOT IN (
            SELECT brn.roomId FROM BookingRoomNight brn
            WHERE brn.stayDate >= :checkIn AND brn.stayDate < :checkOut
        )
        ORDER BY r.roomNumber
        """)
    List<Room> findAvailable(@Param("checkIn") LocalDate checkIn, @Param("checkOut") LocalDate checkOut);

    List<Room> findAllByOrderByFloorAscPositionAsc();

    /** Concurrency-safe state transition (research.md §2) — see
     *  BookingRepository#updateStatusIfCurrent for the full rationale, including why
     *  clearAutomatically = true. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Room r SET r.status = :newStatus WHERE r.id = :id AND r.status = :expected")
    int updateStatusIfCurrent(@Param("id") UUID id,
                              @Param("newStatus") RoomStatus newStatus,
                              @Param("expected") RoomStatus expected);
}
