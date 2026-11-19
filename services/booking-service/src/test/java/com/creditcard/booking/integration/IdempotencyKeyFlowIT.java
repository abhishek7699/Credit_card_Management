package com.creditcard.booking.integration;

import com.creditcard.booking.domain.Room;
import com.creditcard.booking.integration.support.CockroachTestContainer;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.RoomRepository;
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
import org.junit.jupiter.api.BeforeEach;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/bookings with an Idempotency-Key against real CockroachDB. The fake
 * transaction-service counts InitiateTransaction calls, so "no duplicate charge" is
 * measured, not inferred from the response.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = {"transaction.status.updated"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class IdempotencyKeyFlowIT {

    static final AtomicInteger INITIATE_CALLS = new AtomicInteger();

    static class CountingTransactionService extends TransactionServiceGrpc.TransactionServiceImplBase {
        @Override
        public void initiateTransaction(InitiateTransactionRequest request,
                                         StreamObserver<InitiateTransactionResponse> responseObserver) {
            INITIATE_CALLS.incrementAndGet();
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
        private static final String SERVER_NAME = "booking-idempotency-it-transaction-service";

        @Bean(destroyMethod = "shutdownNow")
        Server fakeTransactionServer() throws IOException {
            return InProcessServerBuilder.forName(SERVER_NAME)
                    .directExecutor()
                    .addService(new CountingTransactionService())
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
    private MockMvc mockMvc;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private BookingRepository bookingRepository;

    private List<Room> rooms;

    @BeforeEach
    void setUp() {
        rooms = roomRepository.findAll();
        INITIATE_CALLS.set(0);
    }

    /** Each test books a different room so the assertions never depend on another test's holds. */
    private String bookingJson(Room room) {
        return """
                {
                  "roomId": "%s",
                  "checkIn": "2027-03-01",
                  "checkOut": "2027-03-03",
                  "guestName": "Jane Doe",
                  "card": {"cardNumber": "4111111111111111", "expiry": "12/29", "cvv": "123", "cardholderName": "Jane Doe"}
                }
                """.formatted(room.getId());
    }

    private MvcResult postBooking(Room room, String idempotencyKey) throws Exception {
        var request = post("/api/v1/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bookingJson(room));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return mockMvc.perform(request).andExpect(status().isCreated()).andReturn();
    }

    private static String bookingIdOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.bookingId");
    }

    @Test
    void sameKeyTwiceReturnsSameBookingAndInitiatesPaymentOnce() throws Exception {
        Room room = rooms.get(0);
        long bookingsBefore = bookingRepository.count();

        String first = bookingIdOf(postBooking(room, "idem-same-key"));
        String second = bookingIdOf(postBooking(room, "idem-same-key"));

        assertThat(second).isEqualTo(first);
        assertThat(bookingRepository.count()).isEqualTo(bookingsBefore + 1);
        assertThat(INITIATE_CALLS.get()).isEqualTo(1);
    }

    @Test
    void differentKeysOnDifferentRoomsCreateSeparateBookingsAndPayments() throws Exception {
        long bookingsBefore = bookingRepository.count();

        String a = bookingIdOf(postBooking(rooms.get(1), "idem-key-a"));
        String b = bookingIdOf(postBooking(rooms.get(3), "idem-key-b"));

        assertThat(a).isNotEqualTo(b);
        assertThat(bookingRepository.count()).isEqualTo(bookingsBefore + 2);
        assertThat(INITIATE_CALLS.get()).isEqualTo(2);
    }

    /** A new key must not bypass the room-conflict rule: same room and dates is still a 409. */
    @Test
    void differentKeySameRoomAndDatesIsStillRejectedAsDoubleBooking() throws Exception {
        Room room = rooms.get(4);
        postBooking(room, "idem-key-first");

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", "idem-key-second")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(room)))
                .andExpect(status().isConflict());

        assertThat(INITIATE_CALLS.get()).isEqualTo(1);
    }

    /** No key: exactly today's behavior. A repeated identical request is a room conflict. */
    @Test
    void noKeyRepeatedRequestIsRejectedAsBeforeAndPaysOnce() throws Exception {
        Room room = rooms.get(2);
        long bookingsBefore = bookingRepository.count();

        postBooking(room, null);
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(room)))
                .andExpect(status().isConflict());

        assertThat(bookingRepository.count()).isEqualTo(bookingsBefore + 1);
        assertThat(INITIATE_CALLS.get()).isEqualTo(1);
    }
}
