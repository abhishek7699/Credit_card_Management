package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.RoomBoardResponse;
import com.creditcard.booking.controller.dto.RoomBoardResponse.RoomBoardEntry;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.exception.InvalidRoomStatusTransitionException;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Room-status transitions (front-desk or housekeeping) and the room board — the
 *  staff-facing read view across all of layer 2b. Check-in/check-out live in
 *  BookingService — see its own Javadoc. */
@Service
public class RoomService {

    /**
     * FR-010: the full set of allowed room-status transitions. OCCUPIED has no entry
     * here deliberately — it is reached only via check-in and left only via check-out
     * (to DIRTY); this endpoint never accepts OCCUPIED as a target, and never accepts
     * a transition out of it either (data-model.md's state machine).
     */
    private static final Map<RoomStatus, Set<RoomStatus>> ALLOWED_TRANSITIONS = Map.of(
            RoomStatus.DIRTY, Set.of(RoomStatus.VACANT_CLEAN, RoomStatus.MAINTENANCE),
            RoomStatus.VACANT_CLEAN, Set.of(RoomStatus.MAINTENANCE),
            RoomStatus.MAINTENANCE, Set.of(RoomStatus.VACANT_CLEAN)
    );

    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;

    public RoomService(RoomRepository roomRepository, BookingRepository bookingRepository) {
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
    }

    /**
     * FR-010/FR-011: validates the requested (current, target) pair against
     * ALLOWED_TRANSITIONS, then applies it via the same conditional-update pattern as
     * check-in/check-out (research.md §2) using the just-read current status as the
     * expected value — a concurrent change between the read and the update surfaces as
     * the exact same rejection (plan.md Key Design Decision #4), never a silently
     * wrong write.
     */
    @Transactional
    public Room updateStatus(UUID roomId, RoomStatus requested) {
        Room room = getRoomOrThrow(roomId);
        RoomStatus current = room.getStatus();

        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(requested)) {
            throw new InvalidRoomStatusTransitionException(roomId, current, requested);
        }

        int updated = roomRepository.updateStatusIfCurrent(roomId, requested, current);
        if (updated == 0) {
            throw new InvalidRoomStatusTransitionException(roomId, current, requested);
        }

        return getRoomOrThrow(roomId);
    }

    /** Shared by both the pre-update read and the post-update re-fetch in updateStatus
     *  — one lambda, not two, so the not-found test case covers both call sites. */
    private Room getRoomOrThrow(UUID roomId) {
        return roomRepository.findById(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
    }

    /**
     * FR-012/FR-013: every room, grouped by floor and ordered by position, with the
     * current guest's name/dates present only when OCCUPIED. Two queries, joined in
     * memory by roomId (research.md §3) — at most one CHECKED_IN booking can exist per
     * room at a time, by construction.
     */
    public List<RoomBoardResponse> getBoard() {
        List<Room> rooms = roomRepository.findAllByOrderByFloorAscPositionAsc();
        Map<UUID, Booking> occupantByRoomId = bookingRepository.findByStatus(BookingStatus.CHECKED_IN).stream()
                .collect(Collectors.toMap(Booking::getRoomId, Function.identity()));

        List<RoomBoardResponse> board = new ArrayList<>();
        Integer currentFloor = null;
        List<RoomBoardEntry> currentFloorRooms = null;
        for (Room room : rooms) {
            if (!room.getFloor().equals(currentFloor)) {
                currentFloor = room.getFloor();
                currentFloorRooms = new ArrayList<>();
                board.add(new RoomBoardResponse(currentFloor, currentFloorRooms));
            }
            currentFloorRooms.add(toEntry(room, occupantByRoomId.get(room.getId())));
        }
        return board;
    }

    private static RoomBoardEntry toEntry(Room room, Booking occupant) {
        return new RoomBoardEntry(
                room.getId(),
                room.getRoomNumber(),
                room.getPosition(),
                room.getStatus(),
                occupant != null ? occupant.getGuestName() : null,
                occupant != null ? occupant.getCheckIn() : null,
                occupant != null ? occupant.getCheckOut() : null);
    }
}
