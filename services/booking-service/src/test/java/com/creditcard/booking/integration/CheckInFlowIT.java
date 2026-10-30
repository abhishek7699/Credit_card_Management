package com.creditcard.booking.integration;

import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.booking.service.BookingService;
import com.creditcard.booking.service.RoomService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.creditcard.booking.integration.support.CockroachTestContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US2: front-desk checks a CONFIRMED booking in, immediately reflected on the board
 * (spec.md Story 2). No Kafka/gRPC needed — this layer's check-in is a pure, local
 * state transition, so the booking is set up directly at CONFIRMED rather than
 * replaying the whole payment saga (already covered by layer 2a's own IT suite).
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class CheckInFlowIT {

    @Container
    static GenericContainer<?> cockroach = CockroachTestContainer.newContainer();

    @DynamicPropertySource
    static void cockroachProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> CockroachTestContainer.jdbcUrl(cockroach));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        // KafkaConsumerConfig's @Value has no default and is always component-scanned
        // into the full context — this test never sends/consumes anything, so any
        // address satisfies the bean; Spring Kafka retries connecting in the
        // background without failing context startup or this test.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private BookingService bookingService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @AfterEach
    void tearDown() {
        bookingRepository.deleteAll();
    }

    private Room seedRoom() {
        Room room = new Room();
        room.setRoomNumber("CI-" + UUID.randomUUID().toString().substring(0, 8));
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("100.00"));
        room.setFloor(99);
        room.setPosition((int) (System.nanoTime() % 1_000_000));
        return roomRepository.save(room);
    }

    private Booking seedConfirmedBooking(UUID roomId) {
        Booking booking = new Booking();
        booking.setRoomId(roomId);
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 12, 1));
        booking.setCheckOut(LocalDate.of(2026, 12, 3));
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setTransactionId("txn-checkin-it");
        return bookingRepository.save(booking);
    }

    @Test
    void checkIn_confirmedBookingVacantCleanRoom_reflectsOnBoardAsOccupied() {
        Room room = seedRoom();
        Booking booking = seedConfirmedBooking(room.getId());

        Booking result = bookingService.checkIn(booking.getId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.OCCUPIED);

        boolean foundOccupied = roomService.getBoard().stream()
                .flatMap(floor -> floor.rooms().stream())
                .anyMatch(entry -> entry.roomId().equals(room.getId())
                        && entry.status() == RoomStatus.OCCUPIED
                        && "Jane Doe".equals(entry.guestName()));
        assertThat(foundOccupied).isTrue();
    }

    @Test
    void checkIn_bookingNotConfirmed_rejected_nothingChanges() {
        Room room = seedRoom();
        Booking booking = new Booking();
        booking.setRoomId(room.getId());
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 12, 5));
        booking.setCheckOut(LocalDate.of(2026, 12, 6));
        booking.setStatus(BookingStatus.HELD);
        Booking savedBooking = bookingRepository.save(booking);

        assertThatThrownBy(() -> bookingService.checkIn(savedBooking.getId()))
                .isInstanceOf(com.creditcard.booking.exception.InvalidBookingStateException.class);

        assertThat(bookingRepository.findById(savedBooking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.HELD);
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.VACANT_CLEAN);
    }
}
