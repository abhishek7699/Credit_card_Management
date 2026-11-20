package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.CardDetails;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Charge;
import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.grpc.IdempotencyKeyClientInterceptor;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
import com.creditcard.booking.repository.ChargeRepository;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import com.creditcard.booking.repository.ProcessedEventRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.booking.repository.UnmatchedPaymentOutcomeRepository;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BookingService#checkOut} calls the gRPC stub chain directly when the balance due is
 * greater than zero. A bare Mockito mock of the stub can't exercise that path (grpc-java's
 * {@code withDeadlineAfter} is final), so a real in-process server records what booking actually
 * sends: the idempotency key on each call and how many calls it made.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceCheckOutTest {

    private static final Metadata.Key<String> KEY_HEADER =
            Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER);

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private BookingRoomNightRepository bookingRoomNightRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private ChargeRepository chargeRepository;
    @Mock
    private CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;
    @Mock
    private IdempotencyKeyService idempotencyKeyService;
    @Mock
    private UnmatchedPaymentOutcomeRepository unmatchedPaymentOutcomeRepository;

    private Server fakeServer;
    private ManagedChannel managedChannel;

    /** Idempotency keys seen by the fake server, one per call, in call order. */
    private final List<String> keysSeen = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (managedChannel != null) {
            managedChannel.shutdownNow();
        }
        if (fakeServer != null) {
            fakeServer.shutdownNow();
        }
    }

    /** Starts the fake transaction-service and returns a stub built the way production builds it. */
    private TransactionServiceGrpc.TransactionServiceBlockingStub startFakeTransactionService(
            TransactionServiceGrpc.TransactionServiceImplBase impl) throws Exception {
        String name = "booking-checkout-test-" + UUID.randomUUID();
        ServerInterceptor recordKey = new ServerInterceptor() {
            @Override
            public <Req, Resp> ServerCall.Listener<Req> interceptCall(ServerCall<Req, Resp> call, Metadata headers,
                                                                      ServerCallHandler<Req, Resp> next) {
                keysSeen.add(String.valueOf(headers.get(KEY_HEADER)));
                return next.startCall(call, headers);
            }
        };
        fakeServer = InProcessServerBuilder.forName(name).directExecutor()
                .addService(ServerInterceptors.intercept(impl, recordKey)).build().start();
        managedChannel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return TransactionServiceGrpc.newBlockingStub(
                ClientInterceptors.intercept(managedChannel, new IdempotencyKeyClientInterceptor()));
    }

    private BookingService serviceWith(TransactionServiceGrpc.TransactionServiceBlockingStub stub) {
        CheckoutPaymentLedger ledger = new CheckoutPaymentLedger(bookingRepository, roomRepository,
                checkoutPaymentAttemptRepository, unmatchedPaymentOutcomeRepository);
        return new BookingService(bookingRepository, roomRepository, bookingRoomNightRepository,
                processedEventRepository, chargeRepository, checkoutPaymentAttemptRepository, stub,
                idempotencyKeyService, ledger, "HOTEL-001");
    }

    /** {@code legacyRoomPaymentTransactionId} is set so the room cost cancels back out of the balance,
     *  so the balance is exactly the charge amount these tests care about. */
    private static Booking checkedInBooking(UUID bookingId) {
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", bookingId);
        booking.setRoomId(UUID.randomUUID());
        booking.setGuestName("Jane Doe");
        booking.setGuestPhone("+1-555-0100");
        booking.setCheckIn(LocalDate.of(2026, 11, 1));
        booking.setCheckOut(LocalDate.of(2026, 11, 3));
        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setLegacyRoomPaymentTransactionId("txn-legacy-checkout-test");
        booking.setRoomChargeAmount(new BigDecimal("200.00"));
        return booking;
    }

    private static Charge charge(String amount) {
        Charge charge = new Charge();
        charge.setAmount(new BigDecimal(amount));
        return charge;
    }

    /** Gives every saved attempt the given id, the way the database would assign it. */
    private void assignAttemptId(UUID attemptId) {
        when(checkoutPaymentAttemptRepository.save(any())).thenAnswer(inv -> {
            CheckoutPaymentAttempt attempt = inv.getArgument(0);
            ReflectionTestUtils.setField(attempt, "id", attemptId);
            return attempt;
        });
    }

    private void stubCheckedInBooking(UUID bookingId) {
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(checkedInBooking(bookingId)));
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of(charge("24.50")));
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKOUT_PENDING, BookingStatus.CHECKED_IN))
                .thenReturn(1);
    }

    private static final CardDetails CARD = new CardDetails("4111111111111111", "12/30", "123", "Jane Doe");

    @Test
    void balanceDue_authorizationSucceeds_linksTheTransactionAndMovesToCheckoutPending() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        BookingService service = serviceWith(startFakeTransactionService(new TransactionServiceGrpc.TransactionServiceImplBase() {
            @Override
            public void initiateTransaction(InitiateTransactionRequest request,
                                            StreamObserver<InitiateTransactionResponse> responseObserver) {
                responseObserver.onNext(InitiateTransactionResponse.newBuilder()
                        .setTransactionId("txn-checkout-success").setStatus("PENDING").build());
                responseObserver.onCompleted();
            }
        }));
        stubCheckedInBooking(bookingId);
        assignAttemptId(attemptId);

        service.checkOut(bookingId, CARD);

        ArgumentCaptorHolder saved = new ArgumentCaptorHolder();
        verify(checkoutPaymentAttemptRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.value().getAmount()).isEqualByComparingTo(new BigDecimal("24.50"));
        assertThat(saved.value().getStatus()).isEqualTo(CheckoutAttemptStatus.PENDING);
        verify(checkoutPaymentAttemptRepository).linkTransactionIfAbsent(attemptId, "txn-checkout-success");
        assertThat(keysSeen).containsExactly(attemptId.toString());
    }

    @Test
    void processedButReplyLost_isRetriedWithTheSameKey_andCreatesOneTransaction() throws Exception {
        // The fake transaction-service processes the first call (it records a transaction under the
        // key) but its reply is lost, so the caller sees UNAVAILABLE. The retry reuses the same key and
        // must get the same transaction back, not create a second one.
        UUID bookingId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        Map<String, String> transactionsByKey = new ConcurrentHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        BookingService service = serviceWith(startFakeTransactionService(new TransactionServiceGrpc.TransactionServiceImplBase() {
            @Override
            public void initiateTransaction(InitiateTransactionRequest request,
                                            StreamObserver<InitiateTransactionResponse> responseObserver) {
                String key = keysSeen.get(keysSeen.size() - 1);
                String transactionId = transactionsByKey.computeIfAbsent(key, k -> UUID.randomUUID().toString());
                if (calls.incrementAndGet() == 1) {
                    responseObserver.onError(Status.UNAVAILABLE.withDescription("reply lost after processing").asRuntimeException());
                    return;
                }
                responseObserver.onNext(InitiateTransactionResponse.newBuilder()
                        .setTransactionId(transactionId).setStatus("PENDING").build());
                responseObserver.onCompleted();
            }
        }));
        stubCheckedInBooking(bookingId);
        assignAttemptId(attemptId);

        service.checkOut(bookingId, CARD);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(keysSeen).containsExactly(attemptId.toString(), attemptId.toString());
        assertThat(transactionsByKey).hasSize(1);
        verify(checkoutPaymentAttemptRepository).linkTransactionIfAbsent(attemptId,
                transactionsByKey.get(attemptId.toString()));
    }

    @Test
    void transactionServiceStaysUnavailable_retriesAFixedNumberOfTimes_andLeavesTheAttemptUnlinked() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        BookingService service = serviceWith(startFakeTransactionService(new TransactionServiceGrpc.TransactionServiceImplBase() {
            @Override
            public void initiateTransaction(InitiateTransactionRequest request,
                                            StreamObserver<InitiateTransactionResponse> responseObserver) {
                calls.incrementAndGet();
                responseObserver.onError(Status.UNAVAILABLE.withDescription("down").asRuntimeException());
            }
        }));
        stubCheckedInBooking(bookingId);
        assignAttemptId(attemptId);

        service.checkOut(bookingId, CARD);

        assertThat(calls.get()).isEqualTo(3);
        assertThat(keysSeen).allMatch(attemptId.toString()::equals);
        verify(checkoutPaymentAttemptRepository, never()).linkTransactionIfAbsent(any(), any());
    }

    /** Captures the attempt passed to save, keeping the first one. */
    private static final class ArgumentCaptorHolder {
        private final org.mockito.ArgumentCaptor<CheckoutPaymentAttempt> captor =
                org.mockito.ArgumentCaptor.forClass(CheckoutPaymentAttempt.class);

        CheckoutPaymentAttempt capture() {
            return captor.capture();
        }

        CheckoutPaymentAttempt value() {
            return captor.getValue();
        }
    }
}
