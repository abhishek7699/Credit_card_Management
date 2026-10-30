package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Charge;
import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
import com.creditcard.booking.repository.ChargeRepository;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import com.creditcard.booking.repository.ProcessedEventRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.transaction.grpc.v1.GetTransactionRequest;
import com.creditcard.transaction.grpc.v1.GetTransactionResponse;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link BookingService#checkOut} calls the same gRPC stub chain as
 * {@link BookingService#initiatePayment} when the balance due is greater than zero --
 * see {@link BookingServiceInitiatePaymentTest}'s own javadoc for why a bare Mockito
 * mock of the stub can't exercise this (withDeadlineAfter is final on grpc-java's
 * AbstractStub). A real in-process gRPC server is used here for the same reason.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceCheckOutTest {

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

    private Server fakeServer;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (fakeServer != null) {
            fakeServer.shutdownNow();
        }
    }

    private BookingService serviceBackedBy(TransactionServiceGrpc.TransactionServiceImplBase impl) throws Exception {
        String name = "booking-checkout-test-" + UUID.randomUUID();
        fakeServer = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var stub = TransactionServiceGrpc.newBlockingStub(channel);
        return new BookingService(bookingRepository, roomRepository, bookingRoomNightRepository,
                processedEventRepository, chargeRepository, checkoutPaymentAttemptRepository, stub, "HOTEL-001");
    }

    private static Booking checkedInBooking(UUID bookingId) {
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", bookingId);
        booking.setRoomId(UUID.randomUUID());
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 11, 1));
        booking.setCheckOut(LocalDate.of(2026, 11, 3));
        booking.setStatus(BookingStatus.CHECKED_IN);
        booking.setRoomChargeAmount(new BigDecimal("200.00"));
        return booking;
    }

    private static Charge charge(String amount) {
        Charge charge = new Charge();
        charge.setAmount(new BigDecimal(amount));
        return charge;
    }

    @Test
    void balanceDue_authorizationSucceeds_createsPendingAttemptWithTransactionId_movesToCheckoutPending() throws Exception {
        UUID bookingId = UUID.randomUUID();
        BookingService service = serviceBackedBy(new TransactionServiceGrpc.TransactionServiceImplBase() {
            @Override
            public void initiateTransaction(InitiateTransactionRequest request,
                                              StreamObserver<InitiateTransactionResponse> responseObserver) {
                responseObserver.onNext(InitiateTransactionResponse.newBuilder()
                        .setTransactionId("txn-checkout-success").setStatus("PENDING").build());
                responseObserver.onCompleted();
            }

            @Override
            public void getTransaction(GetTransactionRequest request, StreamObserver<GetTransactionResponse> ro) {
                ro.onNext(GetTransactionResponse.newBuilder().build());
                ro.onCompleted();
            }
        });
        Booking checkedIn = checkedInBooking(bookingId);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(checkedIn));
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of(charge("24.50")));
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKOUT_PENDING, BookingStatus.CHECKED_IN))
                .thenReturn(1);
        when(checkoutPaymentAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        BookingRequest.CardDetails card = new BookingRequest.CardDetails("4111111111111111", "12/30", "123", "Jane Doe");

        service.checkOut(bookingId, card);

        ArgumentCaptor<CheckoutPaymentAttempt> captor = ArgumentCaptor.forClass(CheckoutPaymentAttempt.class);
        org.mockito.Mockito.verify(checkoutPaymentAttemptRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        CheckoutPaymentAttempt savedAttempt = captor.getValue();
        assertThat(savedAttempt.getBookingId()).isEqualTo(bookingId);
        assertThat(savedAttempt.getAmount()).isEqualByComparingTo(new BigDecimal("24.50"));
        assertThat(savedAttempt.getStatus()).isEqualTo(CheckoutAttemptStatus.PENDING);
        assertThat(savedAttempt.getTransactionId()).isEqualTo("txn-checkout-success");
    }

    @Test
    void balanceDue_transactionServiceUnavailable_attemptStaysWithNoTransactionId() throws Exception {
        UUID bookingId = UUID.randomUUID();
        BookingService service = serviceBackedBy(new TransactionServiceGrpc.TransactionServiceImplBase() {
            @Override
            public void initiateTransaction(InitiateTransactionRequest request,
                                              StreamObserver<InitiateTransactionResponse> responseObserver) {
                responseObserver.onError(Status.UNAVAILABLE.withDescription("down").asRuntimeException());
            }
        });
        Booking checkedIn = checkedInBooking(bookingId);
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(checkedIn));
        when(chargeRepository.findByBookingIdOrderByCreatedAtAsc(bookingId)).thenReturn(List.of(charge("24.50")));
        when(bookingRepository.updateStatusIfCurrent(bookingId, BookingStatus.CHECKOUT_PENDING, BookingStatus.CHECKED_IN))
                .thenReturn(1);
        when(checkoutPaymentAttemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        BookingRequest.CardDetails card = new BookingRequest.CardDetails("4111111111111111", "12/30", "123", "Jane Doe");

        service.checkOut(bookingId, card);

        ArgumentCaptor<CheckoutPaymentAttempt> captor = ArgumentCaptor.forClass(CheckoutPaymentAttempt.class);
        org.mockito.Mockito.verify(checkoutPaymentAttemptRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        CheckoutPaymentAttempt savedAttempt = captor.getValue();
        assertThat(savedAttempt.getTransactionId()).isNull();
        assertThat(savedAttempt.getStatus()).isEqualTo(CheckoutAttemptStatus.PENDING);
    }
}
