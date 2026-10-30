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

    @Test
    void getBoard_populatesCurrentAndArrivingBookingIds_fromRealQueries() {
        // currentBookingId, from a real CHECKED_IN row (not just the mocked
        // RoomServiceTest path) —
        Room occupiedRoom = seedRoom(RoomStatus.VACANT_CLEAN);
        Booking confirmedToday = new Booking();
        confirmedToday.setRoomId(occupiedRoom.getId());
        confirmedToday.setGuestName("Checked-In Guest");
        confirmedToday.setCheckIn(LocalDate.now().minusDays(1));
        confirmedToday.setCheckOut(LocalDate.now().plusDays(1));
        confirmedToday.setStatus(BookingStatus.CONFIRMED);
        confirmedToday.setTransactionId("txn-current-it");
        confirmedToday = bookingRepository.save(confirmedToday);
        Booking checkedIn = bookingService.checkIn(confirmedToday.getId());

        // arrivingBookingId, from a real CONFIRMED row whose check-in date is
        // today, against findByStatusAndCheckIn's actual derived query —
        Room arrivingRoom = seedRoom(RoomStatus.VACANT_CLEAN);
        Booking arrivingToday = new Booking();
        arrivingToday.setRoomId(arrivingRoom.getId());
        arrivingToday.setGuestName("Arriving Guest");
        arrivingToday.setCheckIn(LocalDate.now());
        arrivingToday.setCheckOut(LocalDate.now().plusDays(2));
        arrivingToday.setStatus(BookingStatus.CONFIRMED);
        arrivingToday.setTransactionId("txn-arriving-it");
        arrivingToday = bookingRepository.save(arrivingToday);

        // A CONFIRMED booking for a *future* check-in date must NOT appear as
        // arriving — proves the query is scoped to today, not just CONFIRMED.
        Room futureRoom = seedRoom(RoomStatus.VACANT_CLEAN);
        Booking futureBooking = new Booking();
        futureBooking.setRoomId(futureRoom.getId());
        futureBooking.setGuestName("Future Guest");
        futureBooking.setCheckIn(LocalDate.now().plusDays(5));
        futureBooking.setCheckOut(LocalDate.now().plusDays(7));
        futureBooking.setStatus(BookingStatus.CONFIRMED);
        futureBooking.setTransactionId("txn-future-it");
        bookingRepository.save(futureBooking);

        var board = roomService.getBoard().stream()
                .flatMap(floor -> floor.rooms().stream())
                .toList();

        var occupiedEntry = board.stream().filter(e -> e.roomId().equals(occupiedRoom.getId())).findFirst().orElseThrow();
        assertThat(occupiedEntry.status()).isEqualTo(RoomStatus.OCCUPIED);
        assertThat(occupiedEntry.currentBookingId()).isEqualTo(checkedIn.getId());
        assertThat(occupiedEntry.arrivingBookingId()).isNull();

        var arrivingEntry = board.stream().filter(e -> e.roomId().equals(arrivingRoom.getId())).findFirst().orElseThrow();
        assertThat(arrivingEntry.arrivingBookingId()).isEqualTo(arrivingToday.getId());
        assertThat(arrivingEntry.arrivingGuestName()).isEqualTo("Arriving Guest");
        assertThat(arrivingEntry.currentBookingId()).isNull();

        var futureEntry = board.stream().filter(e -> e.roomId().equals(futureRoom.getId())).findFirst().orElseThrow();
        assertThat(futureEntry.arrivingBookingId()).isNull();
        assertThat(futureEntry.arrivingGuestName()).isNull();
    }
}
