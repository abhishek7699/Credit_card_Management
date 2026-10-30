package com.creditcard.booking.integration;

import com.creditcard.booking.integration.support.CockroachTestContainer;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.booking.service.BookingService;
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

/**
 * US3: front-desk checks a CHECKED_IN booking out — booking CHECKED_OUT, room DIRTY,
 * no payment of any kind (spec.md Story 3). No Kafka/gRPC needed, same rationale as
 * CheckInFlowIT.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class CheckOutFlowIT {

    @Container
    static GenericContainer<?> cockroach = CockroachTestContainer.newContainer();

    @DynamicPropertySource
    static void cockroachProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> CockroachTestContainer.jdbcUrl(cockroach));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        // See CheckInFlowIT's identical property for why this is needed here.
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired
    private BookingService bookingService;

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
        room.setRoomNumber("CO-" + UUID.randomUUID().toString().substring(0, 8));
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("100.00"));
        room.setFloor(99);
        room.setPosition((int) (System.nanoTime() % 1_000_000));
        return roomRepository.save(room);
    }

    private Booking seedCheckedInBooking(UUID roomId) {
        Booking booking = new Booking();
        booking.setRoomId(roomId);
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 12, 1));
        booking.setCheckOut(LocalDate.of(2026, 12, 3));
        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setTransactionId("txn-checkout-it");
        return bookingRepository.save(booking);
    }

    @Test
    void checkOut_checkedInBooking_setsRoomDirty_noPaymentSideEffect() {
        Room room = seedRoom();
        room.setStatus(RoomStatus.OCCUPIED);
        roomRepository.save(room);
        Booking booking = seedCheckedInBooking(room.getId());

        Booking result = bookingService.checkOut(booking.getId());

        assertThat(result.getStatus()).isEqualTo(BookingStatus.CHECKED_OUT);
        assertThat(result.getTransactionId()).isEqualTo("txn-checkout-it"); // unchanged by checkout
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.DIRTY);
    }

    @Test
    void checkOut_roomNotOccupied_stillSetsRoomDirty_bookingStateIsAuthoritative() {
        Room room = seedRoom(); // left at the seeded VACANT_CLEAN, never checked in
        Booking booking = seedCheckedInBooking(room.getId());

        bookingService.checkOut(booking.getId());

        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.DIRTY);
    }

    @Test
    void checkOut_bookingNotCheckedIn_rejected_roomUnchanged() {
        Room room = seedRoom();
        room.setStatus(RoomStatus.OCCUPIED);
        roomRepository.save(room);
        Booking booking = new Booking();
        booking.setRoomId(room.getId());
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 12, 5));
        booking.setCheckOut(LocalDate.of(2026, 12, 6));
        booking.setStatus(BookingStatus.CONFIRMED);
        Booking savedBooking = bookingRepository.save(booking);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bookingService.checkOut(savedBooking.getId()))
                .isInstanceOf(com.creditcard.booking.exception.InvalidBookingStateException.class);

        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus())
                .isEqualTo(RoomStatus.OCCUPIED);
    }
}
