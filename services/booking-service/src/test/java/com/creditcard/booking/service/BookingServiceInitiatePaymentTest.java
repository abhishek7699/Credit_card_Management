package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BookingService#initiatePayment} chains {@code stub.withDeadlineAfter(...)},
 * which is {@code final} on grpc-java's AbstractStub — a bare Mockito mock of the stub
 * NPEs on that call (its backing Channel/CallOptions fields are never initialized).
 * A real in-process gRPC server is the correct way to exercise this method.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceInitiatePaymentTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private BookingRoomNightRepository bookingRoomNightRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;

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
        String name = "booking-initiate-payment-test-" + UUID.randomUUID();
        fakeServer = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        var stub = TransactionServiceGrpc.newBlockingStub(channel);
        return new BookingService(bookingRepository, roomRepository, bookingRoomNightRepository,
                processedEventRepository, stub, "HOTEL-001");
    }

    private static Room room(UUID id, String rate) {
        Room room = new Room();
        ReflectionTestUtils.setField(room, "id", id);
        room.setRoomNumber("101");
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal(rate));
        return room;
    }

    private static Booking heldBooking(UUID roomId) {
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", UUID.randomUUID());
        booking.setRoomId(roomId);
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 11, 1));
        booking.setCheckOut(LocalDate.of(2026, 11, 3));
        booking.setStatus(BookingStatus.HELD);
        return booking;
    }

    @Test
    void authorizationSucceeds_savesTransactionIdOnBooking() throws Exception {
        UUID roomId = UUID.randomUUID();
        BookingService service = serviceBackedBy(new TransactionServiceGrpc.TransactionServiceImplBase() {
            @Override
            public void initiateTransaction(InitiateTransactionRequest request,
                                              StreamObserver<InitiateTransactionResponse> responseObserver) {
                responseObserver.onNext(InitiateTransactionResponse.newBuilder()
                        .setTransactionId("txn-success").setStatus("PENDING").build());
                responseObserver.onCompleted();
            }

            @Override
            public void getTransaction(GetTransactionRequest request, StreamObserver<GetTransactionResponse> ro) {
                ro.onNext(GetTransactionResponse.newBuilder().build());
                ro.onCompleted();
            }
        });
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room(roomId, "100.00")));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Booking booking = heldBooking(roomId);

        service.initiatePayment(booking, new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        assertThat(booking.getTransactionId()).isEqualTo("txn-success");
        verify(bookingRepository).save(booking);
    }

    @Test
    void transactionServiceUnavailable_leavesBookingHeldWithNoTransactionId() throws Exception {
        UUID roomId = UUID.randomUUID();
        BookingService service = serviceBackedBy(new TransactionServiceGrpc.TransactionServiceImplBase() {
            @Override
            public void initiateTransaction(InitiateTransactionRequest request,
                                              StreamObserver<InitiateTransactionResponse> responseObserver) {
                responseObserver.onError(Status.UNAVAILABLE.withDescription("down").asRuntimeException());
            }
        });
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room(roomId, "100.00")));
        Booking booking = heldBooking(roomId);

        service.initiatePayment(booking, new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        assertThat(booking.getTransactionId()).isNull();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
        verify(bookingRepository, never()).save(any());
    }
}
