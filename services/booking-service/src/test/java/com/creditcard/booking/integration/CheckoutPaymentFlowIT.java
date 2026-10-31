package com.creditcard.booking.integration;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.controller.dto.ChargeRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.ChargeType;
import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.kafka.event.TransactionStatusEvent;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.ChargeRepository;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import com.creditcard.booking.repository.ProcessedEventRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.booking.service.BookingService;
import com.creditcard.transaction.grpc.v1.GetTransactionRequest;
import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.creditcard.booking.integration.support.CockroachTestContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * US3, full saga per quickstart.md's "Stuck-checkout recovery" scenario included:
 * check in, add a charge, checkout with a card (gRPC always returns a transactionId
 * here — the real AUTHORIZED/DECLINED outcome always arrives later via Kafka, same
 * as the original room-charge saga in BookingConfirmedFlowIT) -> AUTHORIZED ->
 * CHECKED_OUT + room DIRTY; a second scenario for DECLINED; a third for the
 * stuck -> swept -> late-AUTHORIZED path (the sweep itself is called directly
 * rather than waiting on its real 30s/10min schedule — CheckoutAttemptSweepServiceTest already
 * covers the scheduling mechanics; this IT's job is the transactional behavior).
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"transaction.status.updated"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class CheckoutPaymentFlowIT {

    @TestConfiguration
    static class TestKafkaConfig {
        @Bean(name = "txStatusKafkaTemplate")
        KafkaTemplate<String, TransactionStatusEvent> txStatusKafkaTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String brokers) {
            Map<String, Object> props = Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                    JsonSerializer.ADD_TYPE_INFO_HEADERS, false
            );
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }
    }

    /** Always hands back a fresh transactionId with PENDING status -- the real
     *  outcome always arrives later via the transaction.status.updated event, same
     *  as the original room-charge saga. */
    static class FakeTransactionService extends TransactionServiceGrpc.TransactionServiceImplBase {
        @Override
        public void initiateTransaction(InitiateTransactionRequest request,
                                         StreamObserver<InitiateTransactionResponse> responseObserver) {
            responseObserver.onNext(InitiateTransactionResponse.newBuilder()
                    .setTransactionId(UUID.randomUUID().toString())
                    .setStatus("PENDING")
                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void getTransaction(GetTransactionRequest request,
                                    StreamObserver<GetTransactionResponse> responseObserver) {
            responseObserver.onNext(GetTransactionResponse.newBuilder().build());
            responseObserver.onCompleted();
        }
    }

    @TestConfiguration
    static class FakeGrpcConfig {
        private static final String SERVER_NAME = "booking-it-checkout-transaction-service";

        @Bean(destroyMethod = "shutdownNow")
        Server fakeTransactionServer() throws IOException {
            return InProcessServerBuilder.forName(SERVER_NAME)
                    .directExecutor()
                    .addService(new FakeTransactionService())
                    .build()
                    .start();
        }

        @Bean(destroyMethod = "shutdownNow")
        ManagedChannel fakeTransactionChannel(Server fakeTransactionServer) {
            return InProcessChannelBuilder.forName(SERVER_NAME).directExecutor().build();
        }

        @Bean
        @Primary
        TransactionServiceGrpc.TransactionServiceBlockingStub fakeTransactionServiceBlockingStub(
                ManagedChannel fakeTransactionChannel) {
            return TransactionServiceGrpc.newBlockingStub(fakeTransactionChannel);
        }
    }

    @Container
    static GenericContainer<?> cockroach = CockroachTestContainer.newContainer();

    @DynamicPropertySource
    static void cockroachProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> CockroachTestContainer.jdbcUrl(cockroach));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private KafkaTemplate<String, TransactionStatusEvent> txStatusKafkaTemplate;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private ChargeRepository chargeRepository;

    @Autowired
    private CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteAll();
        checkoutPaymentAttemptRepository.deleteAll();
        chargeRepository.deleteAll();
        bookingRepository.deleteAll();
    }

    private Room seedRoom() {
        Room room = new Room();
        room.setRoomNumber("COIT-" + UUID.randomUUID().toString().substring(0, 8));
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("100.00"));
        room.setFloor(99);
        room.setPosition((int) (System.nanoTime() % 1_000_000));
        room.setStatus(RoomStatus.OCCUPIED);
        return roomRepository.save(room);
    }

    private Booking seedCheckedInBooking(UUID roomId) {
        Booking booking = new Booking();
        booking.setRoomId(roomId);
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 12, 1));
        booking.setCheckOut(LocalDate.of(2026, 12, 3));
        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setTransactionId("txn-room-charge-" + UUID.randomUUID());
        booking.setRoomChargeAmount(new BigDecimal("200.00"));
        return bookingRepository.save(booking);
    }

    private void sendOutcome(String transactionId, String outcome) throws Exception {
        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("transaction.status.updated");
        event.setSourceService("transaction-service");
        event.setOccurredAt(Instant.now());
        TransactionStatusEvent.Payload payload = new TransactionStatusEvent.Payload();
        payload.setTransactionId(transactionId);
        payload.setOutcome(outcome);
        event.setPayload(payload);
        txStatusKafkaTemplate.send("transaction.status.updated", event).get();
    }

    @Test
    void checkInAddChargeCheckoutWithCard_authorized_checksOutAndSetsRoomDirty() throws Exception {
        Room room = seedRoom();
        Booking booking = seedCheckedInBooking(room.getId());
        bookingService.addCharge(booking.getId(),
                new ChargeRequest(ChargeType.ROOM_SERVICE, "Club sandwich", new BigDecimal("18.50")));
        BookingRequest.CardDetails card = new BookingRequest.CardDetails("4111111111111111", "12/30", "123", "Jane Doe");

        Booking afterCheckout = bookingService.checkOut(booking.getId(), card);
        assertThat(afterCheckout.getStatus()).isEqualTo(BookingStatus.CHECKOUT_PENDING);
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(RoomStatus.OCCUPIED);

        CheckoutPaymentAttempt attempt = checkoutPaymentAttemptRepository
                .findByBookingIdInAndStatusIn(List.of(booking.getId()), List.of(CheckoutAttemptStatus.PENDING))
                .get(0);
        assertThat(attempt.getAmount()).isEqualByComparingTo(new BigDecimal("18.50"));

        sendOutcome(attempt.getTransactionId(), "AUTHORIZED");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                        .isEqualTo(BookingStatus.CHECKED_OUT));
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(RoomStatus.DIRTY);
    }

    @Test
    void checkoutWithCard_declined_staysCheckedInWithSameBalance_retryable() throws Exception {
        Room room = seedRoom();
        Booking booking = seedCheckedInBooking(room.getId());
        bookingService.addCharge(booking.getId(),
                new ChargeRequest(ChargeType.MINIBAR, "Soda", new BigDecimal("6.00")));
        BookingRequest.CardDetails card = new BookingRequest.CardDetails("4111111111111111", "12/30", "123", "Jane Doe");

        bookingService.checkOut(booking.getId(), card);
        CheckoutPaymentAttempt attempt = checkoutPaymentAttemptRepository
                .findByBookingIdInAndStatusIn(List.of(booking.getId()), List.of(CheckoutAttemptStatus.PENDING))
                .get(0);

        sendOutcome(attempt.getTransactionId(), "DECLINED");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                        .isEqualTo(BookingStatus.CHECKED_IN));
        assertThat(roomRepository.findById(room.getId()).orElseThrow().getStatus()).isEqualTo(RoomStatus.OCCUPIED);
        BigDecimal balance = bookingService.getBalanceDue(bookingService.getById(booking.getId()));
        assertThat(balance).isEqualByComparingTo(new BigDecimal("6.00"));

        // Retryable: a second checkOut call succeeds now that the attempt resolved.
        Booking retry = bookingService.checkOut(booking.getId(), card);
        assertThat(retry.getStatus()).isEqualTo(BookingStatus.CHECKOUT_PENDING);
    }

    @Test
    void stuckCheckoutAttempt_sweptThenLateAuthorized_recordedAsPayment_bookingStaysCheckedIn() throws Exception {
        Room room = seedRoom();
        Booking booking = seedCheckedInBooking(room.getId());
        bookingService.addCharge(booking.getId(),
                new ChargeRequest(ChargeType.LAUNDRY, "Dry cleaning", new BigDecimal("12.00")));
        BookingRequest.CardDetails card = new BookingRequest.CardDetails("4111111111111111", "12/30", "123", "Jane Doe");

        bookingService.checkOut(booking.getId(), card);
        CheckoutPaymentAttempt attempt = checkoutPaymentAttemptRepository
                .findByBookingIdInAndStatusIn(List.of(booking.getId()), List.of(CheckoutAttemptStatus.PENDING))
                .get(0);

        // No outcome ever arrives in time -- the hold-expiry sweep reverts it.
        bookingService.expireCheckoutAttempt(attempt);
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CHECKED_IN);
        assertThat(checkoutPaymentAttemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(CheckoutAttemptStatus.EXPIRED);

        // The AUTHORIZED outcome finally arrives, late.
        sendOutcome(attempt.getTransactionId(), "AUTHORIZED");

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(checkoutPaymentAttemptRepository.findById(attempt.getId()).orElseThrow().getStatus())
                        .isEqualTo(CheckoutAttemptStatus.LATE_AUTHORIZED));
        // Never a reverse transition -- the booking stays CHECKED_IN, balance now
        // reduced to zero since the late payment covers the one charge.
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CHECKED_IN);
        BigDecimal balance = bookingService.getBalanceDue(bookingService.getById(booking.getId()));
        assertThat(balance).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
