package com.creditcard.booking.integration;

import com.creditcard.booking.controller.dto.ChargeRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.ChargeType;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.ChargeRepository;
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
import com.creditcard.booking.integration.support.CockroachTestContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US1: front-desk checks a booking in, adds charges of different types, and sees
 * them as line items with the correct running balance (spec.md Story 1). No
 * Kafka/gRPC needed — same rationale as CheckInFlowIT/CheckOutFlowIT.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class ChargeFlowIT {

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
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ChargeRepository chargeRepository;

    @AfterEach
    void tearDown() {
        chargeRepository.deleteAll();
        bookingRepository.deleteAll();
    }

    private Room seedRoom() {
        Room room = new Room();
        room.setRoomNumber("CH-" + UUID.randomUUID().toString().substring(0, 8));
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
        booking.setTransactionId("txn-charge-it");
        booking.setRoomChargeAmount(new BigDecimal("200.00"));
        return bookingRepository.save(booking);
    }

    @Test
    void checkedInBooking_twoChargesOfDifferentTypes_appearAsLineItemsWithCorrectBalance() {
        Room room = seedRoom();
        Booking booking = seedCheckedInBooking(room.getId());

        bookingService.addCharge(booking.getId(),
                new ChargeRequest(ChargeType.ROOM_SERVICE, "Club sandwich", new BigDecimal("18.50")));
        bookingService.addCharge(booking.getId(),
                new ChargeRequest(ChargeType.MINIBAR, "Two sodas", new BigDecimal("6.00")));

        var charges = bookingService.getCharges(booking.getId());
        assertThat(charges).hasSize(2);
        assertThat(charges.get(0).getType()).isEqualTo(ChargeType.ROOM_SERVICE);
        assertThat(charges.get(0).getDescription()).isEqualTo("Club sandwich");
        assertThat(charges.get(1).getType()).isEqualTo(ChargeType.MINIBAR);

        BigDecimal balance = bookingService.getBalanceDue(bookingService.getById(booking.getId()));
        assertThat(balance).isEqualByComparingTo(new BigDecimal("24.50"));
    }

    @Test
    void addChargeToNonCheckedInBooking_rejected_nothingPersisted() {
        Room room = seedRoom();
        Booking booking = new Booking();
        booking.setRoomId(room.getId());
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 12, 5));
        booking.setCheckOut(LocalDate.of(2026, 12, 6));
        booking.setStatus(BookingStatus.CONFIRMED);
        Booking savedBooking = bookingRepository.save(booking);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bookingService.addCharge(savedBooking.getId(),
                        new ChargeRequest(ChargeType.OTHER, "Late fee", new BigDecimal("10.00"))))
                .isInstanceOf(com.creditcard.booking.exception.InvalidBookingStateException.class);

        assertThat(bookingService.getCharges(savedBooking.getId())).isEmpty();
    }
}
