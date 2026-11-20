package com.creditcard.booking.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingSource;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.integration.support.CockroachTestContainer;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import com.creditcard.transaction.grpc.v1.ValidateCardRequest;
import com.creditcard.transaction.grpc.v1.ValidateCardResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guest pay-later booking with a no-charge card check, end to end through the real controller, service and
 * database (only the gRPC call to transaction-service is faked): a MATCHED card confirms the booking, a
 * NOT_MATCHED or unavailable check rejects it, and in every case the card number and CVC appear in no table
 * and no log line, even with DEBUG (and TRACE for Hibernate, web and gRPC) logging on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class GuestCardValidationFlowIT {

    private static final String PAN = "5102589999999913";
    private static final String CVC = "6173"; // distinctive: not a port, year or id fragment

    @Container
    static GenericContainer<?> cockroach = CockroachTestContainer.newContainer();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> CockroachTestContainer.jdbcUrl(cockroach));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:9092");
    }

    @Autowired MockMvc mockMvc;
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingRoomNightRepository bookingRoomNightRepository;
    @Autowired RoomRepository roomRepository;
    @Autowired JdbcTemplate jdbc;
    @MockBean TransactionServiceGrpc.TransactionServiceBlockingStub stub;

    /** Thread-safe: Kafka and scheduler threads log while the test thread reads. */
    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> list = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            list.add(event);
        }
    }

    private CapturingAppender logs;
    private final List<Logger> raised = new ArrayList<>();
    private final List<Level> original = new ArrayList<>();

    @BeforeEach
    void captureEverything() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new CapturingAppender();
        logs.start();
        root.addAppender(logs);
        raise(root, Level.DEBUG);
        for (String name : new String[] {"org.hibernate", "org.springframework.web", "io.grpc", "com.creditcard"}) {
            raise((Logger) LoggerFactory.getLogger(name), Level.TRACE);
        }
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
    }

    private void raise(Logger logger, Level level) {
        raised.add(logger);
        original.add(logger.getLevel());
        logger.setLevel(level);
    }

    @AfterEach
    void cleanUp() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(logs);
        for (int i = 0; i < raised.size(); i++) {
            raised.get(i).setLevel(original.get(i));
        }
        bookingRoomNightRepository.deleteAll();
        bookingRepository.deleteAll();
    }

    private Room seedRoom() {
        Room room = new Room();
        room.setRoomNumber("GV-" + UUID.randomUUID().toString().substring(0, 8));
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("100.00"));
        room.setFloor(98);
        room.setPosition((int) (System.nanoTime() % 1_000_000));
        return roomRepository.save(room);
    }

    private static String body(UUID roomId, String cvv) {
        LocalDate in = LocalDate.now().plusDays(30);
        return """
                {"roomId":"%s","checkIn":"%s","checkOut":"%s","guestName":"Jane Doe","guestPhone":"+1-555-0100",
                 "card":{"cardNumber":"%s","expiry":"10/50","cvv":"%s","cardholderName":"Jane Doe"}}
                """.formatted(roomId, in, in.plusDays(2), PAN, cvv);
    }

    @Test
    void matchedCard_confirmsTheBooking_andKeepsOnlyVerdictReferenceAndLast4() throws Exception {
        when(stub.validateCard(any())).thenReturn(ValidateCardResponse.newBuilder()
                .setStatus("MATCHED").setReferenceId("AVM_it1").setLast4("9913").build());
        Room room = seedRoom();

        mockMvc.perform(post("/api/v1/public/bookings").contentType(APPLICATION_JSON).content(body(room.getId(), CVC)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        Booking saved = bookingRepository.findAll().get(0);
        assertThat(saved.getSource()).isEqualTo(BookingSource.ONLINE);
        assertThat(saved.getCardValidationStatus()).isEqualTo("MATCHED");
        assertThat(saved.getCardValidationReferenceId()).isEqualTo("AVM_it1");
        assertThat(saved.getCardLast4()).isEqualTo("9913");
        // The card was sent to transaction-service for this one call...
        ArgumentCaptor<ValidateCardRequest> sent = ArgumentCaptor.forClass(ValidateCardRequest.class);
        verify(stub).validateCard(sent.capture());
        assertThat(sent.getValue().getCardNumber()).isEqualTo(PAN);
        assertThat(sent.getValue().getCvv()).isEqualTo(CVC);
        // ...and it is nowhere afterwards.
        assertNoCardInAnyTableOrLog();
    }

    @Test
    void notMatchedCard_rejectsTheBooking_withAClearMessage_andStoresNothing() throws Exception {
        when(stub.validateCard(any())).thenReturn(ValidateCardResponse.newBuilder()
                .setStatus("NOT_MATCHED").setReferenceId("AVM_it2").setLast4("9913").build());
        Room room = seedRoom();

        mockMvc.perform(post("/api/v1/public/bookings").contentType(APPLICATION_JSON).content(body(room.getId(), CVC)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(
                        "We couldn't verify your card. Please check the card details or use a different card."));

        assertThat(bookingRepository.count()).isZero();
        assertThat(bookingRoomNightRepository.count()).isZero();
        assertNoCardInAnyTableOrLog();
    }

    @Test
    void cardCheckUnavailable_failsClosed_withARetryMessage_andStoresNothing() throws Exception {
        when(stub.validateCard(any())).thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
        Room room = seedRoom();

        mockMvc.perform(post("/api/v1/public/bookings").contentType(APPLICATION_JSON).content(body(room.getId(), CVC)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("We couldn't check your card right now. Please try again in a moment."));

        assertThat(bookingRepository.count()).isZero();
        assertNoCardInAnyTableOrLog();
    }

    @Test
    void invalidRequest_isRejectedBeforeAnyCardCheck_andNothingLeaks() throws Exception {
        Room room = seedRoom();

        mockMvc.perform(post("/api/v1/public/bookings").contentType(APPLICATION_JSON).content(body(room.getId(), "")))
                .andExpect(status().isBadRequest());

        assertThat(bookingRepository.count()).isZero();
        assertNoCardInAnyTableOrLog();
    }

    /** Scans every column of every table in the schema, and every log line captured at DEBUG/TRACE. */
    private void assertNoCardInAnyTableOrLog() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);
        assertThat(tables).contains("bookings");
        for (String table : tables) {
            for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM public.\"" + table + "\"")) {
                for (Map.Entry<String, Object> cell : row.entrySet()) {
                    assertThat(cell.getKey()).as("column in " + table)
                            .doesNotContainIgnoringCase("cvv").doesNotContainIgnoringCase("cvc")
                            .doesNotContainIgnoringCase("card_number").doesNotContainIgnoringCase("pan");
                    String value = String.valueOf(cell.getValue());
                    assertThat(value).as(table + "." + cell.getKey()).doesNotContain(PAN).isNotEqualTo(CVC);
                }
            }
        }
        assertThat(logs.list).as("captured log events").isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            String text = event.getFormattedMessage() + " " + throwableText(event.getThrowableProxy());
            assertThat(text).as("log line from " + event.getLoggerName()).doesNotContain(PAN)
                    .doesNotContainPattern("\\b" + CVC + "\\b");
        }
    }

    private static String throwableText(IThrowableProxy proxy) {
        StringBuilder text = new StringBuilder();
        for (IThrowableProxy t = proxy; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append(' ');
        }
        return text.toString();
    }
}
