package com.creditcard.booking.service;

import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.domain.UnmatchedPaymentOutcome;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.booking.repository.UnmatchedPaymentOutcomeRepository;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.LookupTransactionByIdempotencyKeyRequest;
import com.creditcard.transaction.grpc.v1.LookupTransactionByIdempotencyKeyResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutAttemptSweepServiceTest {

    @Mock
    private CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;
    @Mock
    private BookingService bookingService;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private UnmatchedPaymentOutcomeRepository unmatchedPaymentOutcomeRepository;

    private final FakeTransactionService transactionService = new FakeTransactionService();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private Server server;
    private ManagedChannel channel;
    private CheckoutAttemptSweepService service;

    @BeforeEach
    void setUp() throws Exception {
        String name = "sweep-test-" + UUID.randomUUID();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(transactionService).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        CheckoutPaymentLedger ledger = new CheckoutPaymentLedger(bookingRepository, roomRepository,
                checkoutPaymentAttemptRepository, unmatchedPaymentOutcomeRepository);
        service = new CheckoutAttemptSweepService(checkoutPaymentAttemptRepository, bookingService, ledger,
                TransactionServiceGrpc.newBlockingStub(channel), meterRegistry, 10L, Duration.ofHours(1));
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private static CheckoutPaymentAttempt checkoutAttempt(UUID id, UUID bookingId) {
        CheckoutPaymentAttempt attempt = new CheckoutPaymentAttempt();
        ReflectionTestUtils.setField(attempt, "id", id);
        attempt.setBookingId(bookingId);
        attempt.setAmount(BigDecimal.TEN);
        attempt.setStatus(CheckoutAttemptStatus.PENDING);
        return attempt;
    }

    private void stuckAttempts(CheckoutPaymentAttempt... attempts) {
        when(checkoutPaymentAttemptRepository.findByStatusAndCreatedAtBefore(eq(CheckoutAttemptStatus.PENDING), any(Instant.class)))
                .thenReturn(List.of(attempts));
    }

    @Test
    void approvedInTransactionService_marksTheAttemptAndBookingCheckedOut_withoutResendingInitiate() {
        UUID bookingId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(UUID.randomUUID(), bookingId);
        transactionService.reply(attempt.getId(), "txn-approved", "AUTHORIZED");
        stuckAttempts(attempt);
        when(checkoutPaymentAttemptRepository.findById(attempt.getId())).thenReturn(Optional.of(attempt));
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", bookingId);
        booking.setRoomId(roomId);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKOUT_PENDING))
                .thenReturn(1);
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.PENDING)).thenReturn(1);

        service.expireStuckCheckoutAttempts();

        verify(checkoutPaymentAttemptRepository).linkTransactionIfAbsent(attempt.getId(), "txn-approved");
        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKOUT_PENDING);
        verify(roomRepository).setStatus(roomId, RoomStatus.DIRTY);
        verify(bookingService, never()).expireCheckoutAttempt(any());
        assertThat(transactionService.initiateCalls()).isZero();
    }

    @Test
    void unknownToTransactionService_expiresTheAttemptAndRevertsTheBookingToCheckedIn_withoutResendingInitiate() {
        CheckoutPaymentAttempt attempt = checkoutAttempt(UUID.randomUUID(), UUID.randomUUID());
        stuckAttempts(attempt);

        service.expireStuckCheckoutAttempts();

        verify(bookingService).expireCheckoutAttempt(attempt);
        assertThat(transactionService.initiateCalls()).isZero();
    }

    @Test
    void stillPendingInTransactionService_isExpiredButNotMarkedApproved() {
        CheckoutPaymentAttempt attempt = checkoutAttempt(UUID.randomUUID(), UUID.randomUUID());
        transactionService.reply(attempt.getId(), "txn-slow", "PENDING");
        stuckAttempts(attempt);

        service.expireStuckCheckoutAttempts();

        verify(bookingService).expireCheckoutAttempt(attempt);
        verify(checkoutPaymentAttemptRepository, never()).updateStatusIfCurrent(any(), any(), any());
    }

    @Test
    void lookupFails_leavesTheAttemptPending_andDoesNotGuessAnExpiry() {
        CheckoutPaymentAttempt attempt = checkoutAttempt(UUID.randomUUID(), UUID.randomUUID());
        transactionService.failLookups();
        stuckAttempts(attempt);

        service.expireStuckCheckoutAttempts();

        verify(bookingService, never()).expireCheckoutAttempt(any());
        assertThat(transactionService.initiateCalls()).isZero();
    }

    @Test
    void expireStuckCheckoutAttempts_noneStuck_expiresNothing() {
        stuckAttempts();

        service.expireStuckCheckoutAttempts();

        verify(bookingService, never()).expireCheckoutAttempt(any());
    }

    @Test
    void oneFailsToExpire_othersStillProcessed() {
        CheckoutPaymentAttempt failing = checkoutAttempt(UUID.randomUUID(), UUID.randomUUID());
        CheckoutPaymentAttempt ok = checkoutAttempt(UUID.randomUUID(), UUID.randomUUID());
        stuckAttempts(failing, ok);
        doThrow(new RuntimeException("db hiccup")).when(bookingService).expireCheckoutAttempt(failing);

        service.expireStuckCheckoutAttempts();

        verify(bookingService, times(1)).expireCheckoutAttempt(failing);
        verify(bookingService, times(1)).expireCheckoutAttempt(ok);
    }

    @Test
    void parkedOutcomeWhoseAttemptIsNowLinked_isAppliedAndItsRowDeleted() {
        UUID bookingId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        CheckoutPaymentAttempt attempt = checkoutAttempt(UUID.randomUUID(), bookingId);
        UnmatchedPaymentOutcome parked = mock(UnmatchedPaymentOutcome.class);
        when(parked.getTransactionId()).thenReturn("txn-late");
        when(parked.getOutcome()).thenReturn("AUTHORIZED");
        when(unmatchedPaymentOutcomeRepository.findAll()).thenReturn(List.of(parked));
        when(unmatchedPaymentOutcomeRepository.findById("txn-late")).thenReturn(Optional.of(parked));
        when(checkoutPaymentAttemptRepository.findByTransactionId("txn-late")).thenReturn(Optional.of(attempt));
        when(checkoutPaymentAttemptRepository.updateStatusIfCurrent(
                attempt.getId(), CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.PENDING)).thenReturn(1);
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", bookingId);
        booking.setRoomId(roomId);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKOUT_PENDING))
                .thenReturn(1);
        stuckAttempts();

        service.expireStuckCheckoutAttempts();

        verify(bookingRepository).updateStatusIfCurrent(bookingId, BookingStatus.CHECKED_OUT, BookingStatus.CHECKOUT_PENDING);
        verify(unmatchedPaymentOutcomeRepository).deleteById("txn-late");
    }

    @Test
    void parkedOutcomeStillUnmatchedPastTheThreshold_setsTheGaugeAndIsNeverDeleted() {
        UnmatchedPaymentOutcome stale = mock(UnmatchedPaymentOutcome.class);
        when(unmatchedPaymentOutcomeRepository.findByReceivedAtBefore(any(Instant.class))).thenReturn(List.of(stale));
        stuckAttempts();

        service.expireStuckCheckoutAttempts();

        assertThat(meterRegistry.get("booking.checkout.unmatched_outcomes.stale").gauge().value()).isEqualTo(1.0);
        verify(unmatchedPaymentOutcomeRepository, never()).deleteById(anyString());
    }

    /** A transaction-service that answers lookups only, and counts any InitiateTransaction it receives. */
    private static final class FakeTransactionService extends TransactionServiceGrpc.TransactionServiceImplBase {

        private final Map<String, LookupTransactionByIdempotencyKeyResponse> replies = new ConcurrentHashMap<>();
        private final AtomicInteger initiateCalls = new AtomicInteger();
        private volatile boolean lookupsFail;

        void reply(UUID key, String transactionId, String status) {
            replies.put(key.toString(), LookupTransactionByIdempotencyKeyResponse.newBuilder()
                    .setFound(true).setTransactionId(transactionId).setStatus(status).build());
        }

        void failLookups() {
            lookupsFail = true;
        }

        int initiateCalls() {
            return initiateCalls.get();
        }

        @Override
        public void initiateTransaction(InitiateTransactionRequest request,
                                        StreamObserver<InitiateTransactionResponse> responseObserver) {
            initiateCalls.incrementAndGet();
            responseObserver.onError(Status.UNIMPLEMENTED.withDescription("sweep must never initiate").asRuntimeException());
        }

        @Override
        public void lookupTransactionByIdempotencyKey(LookupTransactionByIdempotencyKeyRequest request,
                                                      StreamObserver<LookupTransactionByIdempotencyKeyResponse> responseObserver) {
            if (lookupsFail) {
                responseObserver.onError(Status.UNAVAILABLE.withDescription("down").asRuntimeException());
                return;
            }
            responseObserver.onNext(replies.getOrDefault(request.getIdempotencyKey(),
                    LookupTransactionByIdempotencyKeyResponse.getDefaultInstance()));
            responseObserver.onCompleted();
        }
    }
}
