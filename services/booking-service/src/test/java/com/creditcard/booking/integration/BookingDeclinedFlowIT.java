package com.creditcard.booking.integration;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CancellationReason;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.kafka.event.TransactionStatusEvent;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * US2: a declined payment cancels the booking and frees the room immediately
 * (FR-011) — quickstart.md Story 2. Mirrors BookingConfirmedFlowIT's in-process fake
 * transaction-service, since mocking the generated gRPC stub directly doesn't work
 * (AbstractStub#withDeadlineAfter is final).
 */
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"transaction.status.updated"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class BookingDeclinedFlowIT {

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
        private static final String SERVER_NAME = "booking-declined-it-transaction-service";

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
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    @DynamicPropertySource
    static void postgresProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
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
    private BookingRoomNightRepository bookingRoomNightRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @AfterEach
    void tearDown() {
        processedEventRepository.deleteAll();
        bookingRoomNightRepository.deleteAll();
        bookingRepository.deleteAll();
    }

    private Room seedRoom() {
        Room room = new Room();
        room.setRoomNumber("DC-" + UUID.randomUUID().toString().substring(0, 8));
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("100.00"));
        room.setFloor(99); // out of the real 1-3 seeded range, never collides
        room.setPosition((int) (System.nanoTime() % 1_000_000));
        return roomRepository.save(room);
    }

    @Test
    void heldBooking_becomesCancelled_whenDeclinedEventArrives_andRoomReappears() throws Exception {
        Room room = seedRoom();
        LocalDate checkIn = LocalDate.of(2026, 11, 10);
        LocalDate checkOut = LocalDate.of(2026, 11, 12);
        BookingRequest request = new BookingRequest(
                room.getId(), checkIn, checkOut, "Jane Doe",
                new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane Doe"));

        Booking booking = bookingService.createHeldBooking(request);
        bookingService.initiatePayment(booking, request.card());
        String transactionId = bookingRepository.findById(booking.getId()).orElseThrow().getTransactionId();
        assertThat(transactionId).isNotBlank();

        // Room must NOT be available while HELD.
        assertThat(roomRepository.findAvailable(checkIn, checkOut)).extracting(Room::getId)
                .doesNotContain(room.getId());

        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("transaction.status.updated");
        event.setSourceService("transaction-service");
        event.setOccurredAt(Instant.now());
        TransactionStatusEvent.Payload payload = new TransactionStatusEvent.Payload();
        payload.setTransactionId(transactionId);
        payload.setOutcome("DECLINED");
        event.setPayload(payload);

        txStatusKafkaTemplate.send("transaction.status.updated", event).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Booking reloaded = bookingRepository.findById(booking.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            assertThat(reloaded.getCancellationReason()).isEqualTo(CancellationReason.PAYMENT_DECLINED);
        });

        assertThat(bookingRoomNightRepository.findAll()).isEmpty();
        assertThat(roomRepository.findAvailable(checkIn, checkOut)).extracting(Room::getId)
                .contains(room.getId());
    }
}
