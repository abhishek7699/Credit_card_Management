package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.RoomBoardResponse;
import com.creditcard.booking.controller.dto.RoomBoardResponse.RoomBoardEntry;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.RoomRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Room-status transitions (check-in/check-out live in BookingService — see its own
 *  Javadoc) and the room board — the staff-facing read view across all of layer 2b. */
@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;

    public RoomService(RoomRepository roomRepository, BookingRepository bookingRepository) {
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
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
