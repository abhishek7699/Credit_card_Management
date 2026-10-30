package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.exception.RoomUnavailableException;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
import com.creditcard.booking.repository.RoomRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FR-007 / research.md §1: at most one of two concurrent booking attempts for the same
 * room and overlapping dates reaches HELD — the other is rejected via the
 * booking_room_nights unique-constraint violation, never a double-booked room.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class BookingServiceConcurrencyTest {

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

    @Autowired
    private BookingRoomNightRepository bookingRoomNightRepository;

    @AfterEach
    void tearDown() {
        bookingRoomNightRepository.deleteAll();
        bookingRepository.deleteAll();
    }

    @Test
    void twoConcurrentBookings_sameRoomOverlappingDates_exactlyOneSucceeds() throws Exception {
        Room room = new Room();
        room.setRoomNumber("CONC-" + UUID.randomUUID().toString().substring(0, 8));
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("150.00"));
        room.setFloor(99); // out of the real 1-3 seeded range, never collides
        room.setPosition((int) (System.nanoTime() % 1_000_000));
        room = roomRepository.save(room);

        BookingRequest request = new BookingRequest(
                room.getId(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3), "Guest",
                new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Guest"));

        int attempts = 8;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    bookingService.createHeldBooking(request);
                    succeeded.incrementAndGet();
                } catch (RoomUnavailableException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(attempts - 1);
        assertThat(bookingRoomNightRepository.findAll()).hasSize(2); // one booking, 2 nights
    }
}
