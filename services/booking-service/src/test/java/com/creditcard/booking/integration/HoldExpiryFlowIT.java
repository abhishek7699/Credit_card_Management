package com.creditcard.booking.integration;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CancellationReason;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.booking.service.BookingService;
import com.creditcard.booking.service.HoldExpiryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US3: a HELD booking with no payment outcome is auto-cancelled once it has been held
 * longer than the configured hold window (FR-015), and the room becomes bookable again
 * — quickstart.md Story 3. created_at is backdated directly via SQL since the column is
 * {@code updatable = false} (Booking.java) — intentionally, so application code can
 * never silently rewrite a booking's own creation time.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class HoldExpiryFlowIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    @DynamicPropertySource
    static void postgresProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private BookingService bookingService;

    @Autowired
    private HoldExpiryService holdExpiryService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingRoomNightRepository bookingRoomNightRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        bookingRoomNightRepository.deleteAll();
        bookingRepository.deleteAll();
    }

    private Room seedRoom() {
        Room room = new Room();
        room.setRoomNumber("HX-" + UUID.randomUUID().toString().substring(0, 8));
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("100.00"));
        return roomRepository.save(room);
    }

    private void backdateCreatedAt(UUID bookingId, Instant when) {
        jdbcTemplate.update("UPDATE bookings SET created_at = ? WHERE id = ?",
                Timestamp.from(when), bookingId);
    }

    @Test
    void abandonedHeldBooking_expiresAndRoomBecomesAvailableAgain() {
        Room room = seedRoom();
        LocalDate checkIn = LocalDate.of(2026, 11, 20);
        LocalDate checkOut = LocalDate.of(2026, 11, 22);
        BookingRequest request = new BookingRequest(room.getId(), checkIn, checkOut, "Jane Doe",
                new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane Doe"));

        Booking booking = bookingService.createHeldBooking(request);
        backdateCreatedAt(booking.getId(), Instant.now().minus(11, ChronoUnit.MINUTES)); // past the 10-min default

        holdExpiryService.expireAbandonedHolds();

        Booking reloaded = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(reloaded.getCancellationReason()).isEqualTo(CancellationReason.HOLD_EXPIRED);
        assertThat(bookingRoomNightRepository.findAll()).isEmpty();
        assertThat(roomRepository.findAvailable(checkIn, checkOut)).extracting(Room::getId)
                .contains(room.getId());
    }

    @Test
    void freshHeldBooking_withinHoldWindow_isLeftUntouched() {
        Room room = seedRoom();
        LocalDate checkIn = LocalDate.of(2026, 11, 25);
        LocalDate checkOut = LocalDate.of(2026, 11, 27);
        BookingRequest request = new BookingRequest(room.getId(), checkIn, checkOut, "Jane Doe",
                new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane Doe"));

        Booking booking = bookingService.createHeldBooking(request); // created_at = now, well inside the window

        holdExpiryService.expireAbandonedHolds();

        Booking reloaded = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void alreadyConfirmedBooking_evenIfOld_isNotAffectedByExpiry() {
        Room room = seedRoom();
        LocalDate checkIn = LocalDate.of(2026, 11, 28);
        LocalDate checkOut = LocalDate.of(2026, 11, 29);
        BookingRequest request = new BookingRequest(room.getId(), checkIn, checkOut, "Jane Doe",
                new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane Doe"));

        Booking booking = bookingService.createHeldBooking(request);
        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);
        backdateCreatedAt(booking.getId(), Instant.now().minus(1, ChronoUnit.HOURS));

        holdExpiryService.expireAbandonedHolds();

        Booking reloaded = bookingRepository.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }
}
