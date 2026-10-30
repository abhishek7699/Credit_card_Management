package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.RoomBoardResponse;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.exception.InvalidRoomStatusTransitionException;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private BookingRepository bookingRepository;

    private RoomService service;

    @BeforeEach
    void setUp() {
        service = new RoomService(roomRepository, bookingRepository);
    }

    private static Room room(UUID id, String number, int floor, int position, RoomStatus status) {
        Room room = new Room();
        ReflectionTestUtils.setField(room, "id", id);
        room.setRoomNumber(number);
        room.setRoomType("STANDARD");
        room.setFloor(floor);
        room.setPosition(position);
        room.setStatus(status);
        return room;
    }

    private static Booking checkedInBooking(UUID roomId) {
        Booking booking = new Booking();
        booking.setRoomId(roomId);
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 12, 1));
        booking.setCheckOut(LocalDate.of(2026, 12, 3));
        booking.setStatus(BookingStatus.CHECKED_IN);
        return booking;
    }

    @Test
    void getBoard_groupsByFloor_ordersByPosition_andOnlyOccupiedRoomsCarryGuestDetails() {
        UUID occupiedRoomId = UUID.randomUUID();
        Room floor1Pos1 = room(UUID.randomUUID(), "101", 1, 1, RoomStatus.VACANT_CLEAN);
        Room floor1Pos2 = room(occupiedRoomId, "102", 1, 2, RoomStatus.OCCUPIED);
        Room floor2Pos1 = room(UUID.randomUUID(), "201", 2, 1, RoomStatus.DIRTY);
        when(roomRepository.findAllByOrderByFloorAscPositionAsc())
                .thenReturn(List.of(floor1Pos1, floor1Pos2, floor2Pos1));
        when(bookingRepository.findByStatus(BookingStatus.CHECKED_IN))
                .thenReturn(List.of(checkedInBooking(occupiedRoomId)));

        List<RoomBoardResponse> board = service.getBoard();

        assertThat(board).hasSize(2);
        assertThat(board.get(0).floor()).isEqualTo(1);
        assertThat(board.get(0).rooms()).hasSize(2);
        assertThat(board.get(0).rooms().get(0).roomNumber()).isEqualTo("101");
        assertThat(board.get(0).rooms().get(0).guestName()).isNull();
        assertThat(board.get(0).rooms().get(1).roomNumber()).isEqualTo("102");
        assertThat(board.get(0).rooms().get(1).status()).isEqualTo(RoomStatus.OCCUPIED);
        assertThat(board.get(0).rooms().get(1).guestName()).isEqualTo("Jane Doe");
        assertThat(board.get(0).rooms().get(1).checkIn()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(board.get(0).rooms().get(1).checkOut()).isEqualTo(LocalDate.of(2026, 12, 3));

        assertThat(board.get(1).floor()).isEqualTo(2);
        assertThat(board.get(1).rooms()).hasSize(1);
        assertThat(board.get(1).rooms().get(0).guestName()).isNull();
        assertThat(board.get(1).rooms().get(0).checkIn()).isNull();
    }

    @Test
    void getBoard_noRooms_returnsEmptyBoard() {
        when(roomRepository.findAllByOrderByFloorAscPositionAsc()).thenReturn(List.of());
        when(bookingRepository.findByStatus(BookingStatus.CHECKED_IN)).thenReturn(List.of());

        assertThat(service.getBoard()).isEmpty();
    }

    @Test
    void updateStatus_dirtyToVacantClean_allowed() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId, "101", 1, 1, RoomStatus.DIRTY);
        Room updated = room(roomId, "101", 1, 1, RoomStatus.VACANT_CLEAN);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room)).thenReturn(Optional.of(updated));
        when(roomRepository.updateStatusIfCurrent(roomId, RoomStatus.VACANT_CLEAN, RoomStatus.DIRTY)).thenReturn(1);

        Room result = service.updateStatus(roomId, RoomStatus.VACANT_CLEAN);

        assertThat(result.getStatus()).isEqualTo(RoomStatus.VACANT_CLEAN);
    }

    @Test
    void updateStatus_dirtyToMaintenance_allowed() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId, "101", 1, 1, RoomStatus.DIRTY);
        Room updated = room(roomId, "101", 1, 1, RoomStatus.MAINTENANCE);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room)).thenReturn(Optional.of(updated));
        when(roomRepository.updateStatusIfCurrent(roomId, RoomStatus.MAINTENANCE, RoomStatus.DIRTY)).thenReturn(1);

        Room result = service.updateStatus(roomId, RoomStatus.MAINTENANCE);

        assertThat(result.getStatus()).isEqualTo(RoomStatus.MAINTENANCE);
    }

    @Test
    void updateStatus_vacantCleanToMaintenance_allowed() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId, "101", 1, 1, RoomStatus.VACANT_CLEAN);
        Room updated = room(roomId, "101", 1, 1, RoomStatus.MAINTENANCE);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room)).thenReturn(Optional.of(updated));
        when(roomRepository.updateStatusIfCurrent(roomId, RoomStatus.MAINTENANCE, RoomStatus.VACANT_CLEAN)).thenReturn(1);

        Room result = service.updateStatus(roomId, RoomStatus.MAINTENANCE);

        assertThat(result.getStatus()).isEqualTo(RoomStatus.MAINTENANCE);
    }

    @Test
    void updateStatus_maintenanceToVacantClean_allowed() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId, "101", 1, 1, RoomStatus.MAINTENANCE);
        Room updated = room(roomId, "101", 1, 1, RoomStatus.VACANT_CLEAN);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room)).thenReturn(Optional.of(updated));
        when(roomRepository.updateStatusIfCurrent(roomId, RoomStatus.VACANT_CLEAN, RoomStatus.MAINTENANCE)).thenReturn(1);

        Room result = service.updateStatus(roomId, RoomStatus.VACANT_CLEAN);

        assertThat(result.getStatus()).isEqualTo(RoomStatus.VACANT_CLEAN);
    }

    @Test
    void updateStatus_vacantCleanToDirty_rejected_noUpdateAttempted() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId, "101", 1, 1, RoomStatus.VACANT_CLEAN);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.updateStatus(roomId, RoomStatus.DIRTY))
                .isInstanceOf(InvalidRoomStatusTransitionException.class);

        verify(roomRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void updateStatus_toOccupied_alwaysRejected() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId, "101", 1, 1, RoomStatus.VACANT_CLEAN);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.updateStatus(roomId, RoomStatus.OCCUPIED))
                .isInstanceOf(InvalidRoomStatusTransitionException.class);

        verify(roomRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(RoomStatus.class)
    void updateStatus_fromOccupied_alwaysRejected(RoomStatus target) {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId, "101", 1, 1, RoomStatus.OCCUPIED);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.updateStatus(roomId, target))
                .isInstanceOf(InvalidRoomStatusTransitionException.class);

        verify(roomRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void updateStatus_sameStatus_rejected() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId, "101", 1, 1, RoomStatus.VACANT_CLEAN);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> service.updateStatus(roomId, RoomStatus.VACANT_CLEAN))
                .isInstanceOf(InvalidRoomStatusTransitionException.class);

        verify(roomRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void updateStatus_lostRace_rejected() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId, "101", 1, 1, RoomStatus.DIRTY);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomRepository.updateStatusIfCurrent(roomId, RoomStatus.VACANT_CLEAN, RoomStatus.DIRTY)).thenReturn(0);

        assertThatThrownBy(() -> service.updateStatus(roomId, RoomStatus.VACANT_CLEAN))
                .isInstanceOf(InvalidRoomStatusTransitionException.class);
    }

    @Test
    void updateStatus_roomNotFound_throws() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(roomId, RoomStatus.VACANT_CLEAN))
                .isInstanceOf(RoomNotFoundException.class);
    }
}
