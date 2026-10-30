package com.creditcard.booking.integration;

import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.exception.InvalidRoomStatusTransitionException;
import com.creditcard.booking.integration.support.CockroachTestContainer;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.booking.service.BookingService;
import com.creditcard.booking.service.RoomAvailabilityService;
import com.creditcard.booking.service.RoomService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US4: housekeeping/front-desk status updates (FR-010/FR-011), the MAINTENANCE
 * exclusion from availability search (FR-002 vs. a MAINTENANCE room with no
 * conflicting booking calendar at all — the only way to prove the exclusion is
 * status-based, not calendar-based), and the full e2e-step-14-style cycle: book(seed
 * CONFIRMED) -> check-in -> board shows OCCUPIED -> check-out -> DIRTY -> housekeeping
 * cleans -> VACANT_CLEAN. No Kafka/gRPC needed, same rationale as CheckInFlowIT.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class RoomStatusFlowIT {

    @Container
    static GenericContainer<?> cockroach = CockroachTestContainer.newContainer();

    @DynamicPropertySource
    static void cockroachProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> CockroachTestContainer.jdbcUrl(cockroach));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private BookingService bookingService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomAvailabilityService roomAvailabilityService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @AfterEach
    void tearDown() {
        bookingRepository.deleteAll();
    }

    private Room seedRoom(RoomStatus status) {
        Room room = new Room();
        room.setRoomNumber("RS-" + UUID.randomUUID().toString().substring(0, 8));
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("100.00"));
        room.setFloor(99);
        room.setPosition((int) (System.nanoTime() % 1_000_000));
        room.setStatus(status);
        return roomRepository.save(room);
    }

    @Test
    void updateStatus_dirtyToVacantClean_persists() {
        Room room = seedRoom(RoomStatus.DIRTY);

        Room result = roomService.updateStatus(room.getId(), RoomStatus.VACANT_CLEAN);

        assertThat(result.getStatus()).isEqualTo(RoomStatus.VACANT_CLEAN);
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.VACANT_CLEAN);
    }

    @Test
    void updateStatus_toOccupied_rejected_roomUnchanged() {
        Room room = seedRoom(RoomStatus.VACANT_CLEAN);

        assertThatThrownBy(() -> roomService.updateStatus(room.getId(), RoomStatus.OCCUPIED))
                .isInstanceOf(InvalidRoomStatusTransitionException.class);

        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.VACANT_CLEAN);
    }

    @Test
    void findAvailable_excludesMaintenanceRoom_evenWithNoConflictingBooking() {
        Room maintenanceRoom = seedRoom(RoomStatus.MAINTENANCE);
        Room vacantRoom = seedRoom(RoomStatus.VACANT_CLEAN);
        LocalDate checkIn = LocalDate.of(2027, 1, 10);
        LocalDate checkOut = LocalDate.of(2027, 1, 12);
        // Neither room has any booking at all for these dates — a calendar-only check
        // would wrongly return both; only the MAINTENANCE status exclusion keeps the
        // maintenance room out.

        var available = roomAvailabilityService.findAvailable(checkIn, checkOut);

        assertThat(available).extracting(Room::getId).contains(vacantRoom.getId());
        assertThat(available).extracting(Room::getId).doesNotContain(maintenanceRoom.getId());
    }

    @Test
    void fullLifecycle_bookToConfirmedCheckInBoardCheckOutHousekeepingClean() {
        Room room = seedRoom(RoomStatus.VACANT_CLEAN);
        Booking booking = new Booking();
        booking.setRoomId(room.getId());
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2027, 2, 1));
        booking.setCheckOut(LocalDate.of(2027, 2, 3));
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setTransactionId("txn-lifecycle-it");
        booking = bookingRepository.save(booking);

        // check-in -> booking CHECKED_IN, room OCCUPIED
        Booking checkedIn = bookingService.checkIn(booking.getId());
        assertThat(checkedIn.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.OCCUPIED);

        // board shows OCCUPIED with the guest's name
        UUID roomId = room.getId();
        boolean occupiedOnBoard = roomService.getBoard().stream()
                .flatMap(floor -> floor.rooms().stream())
                .anyMatch(entry -> entry.roomId().equals(roomId)
                        && entry.status() == RoomStatus.OCCUPIED
                        && "Jane Doe".equals(entry.guestName()));
        assertThat(occupiedOnBoard).isTrue();

        // check-out -> booking CHECKED_OUT, room DIRTY
        Booking checkedOut = bookingService.checkOut(booking.getId());
        assertThat(checkedOut.getStatus()).isEqualTo(BookingStatus.CHECKED_OUT);
        assertThat(roomRepository.findById(roomId).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.DIRTY);

        // housekeeping cleans -> VACANT_CLEAN
        Room cleaned = roomService.updateStatus(roomId, RoomStatus.VACANT_CLEAN);
        assertThat(cleaned.getStatus()).isEqualTo(RoomStatus.VACANT_CLEAN);

        boolean vacantOnBoard = roomService.getBoard().stream()
                .flatMap(floor -> floor.rooms().stream())
                .anyMatch(entry -> entry.roomId().equals(roomId)
                        && entry.status() == RoomStatus.VACANT_CLEAN
                        && entry.guestName() == null);
        assertThat(vacantOnBoard).isTrue();
    }
}
