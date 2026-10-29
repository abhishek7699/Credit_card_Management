package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.exception.BookingNotFoundException;
import com.creditcard.booking.exception.InvalidDateRangeException;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.exception.RoomUnavailableException;
import com.creditcard.booking.kafka.BookingNotFoundForTransactionException;
import com.creditcard.booking.kafka.event.TransactionStatusEvent;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.BookingRoomNightRepository;
import com.creditcard.booking.repository.ProcessedEventRepository;
import com.creditcard.booking.repository.RoomRepository;
import com.creditcard.transaction.grpc.v1.TransactionServiceGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private BookingRoomNightRepository bookingRoomNightRepository;
    @Mock
    private ProcessedEventRepository processedEventRepository;
    @Mock
    private TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceBlockingStub;

    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, roomRepository, bookingRoomNightRepository,
                processedEventRepository, transactionServiceBlockingStub, "HOTEL-001");
    }

    private static Room room(UUID id) {
        Room room = new Room();
        ReflectionTestUtils.setField(room, "id", id);
        room.setRoomNumber("101");
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("100.00"));
        return room;
    }

    private static Booking booking(UUID id, BookingStatus status, String transactionId) {
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", id);
        booking.setRoomId(UUID.randomUUID());
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 11, 1));
        booking.setCheckOut(LocalDate.of(2026, 11, 3));
        booking.setStatus(status);
        booking.setTransactionId(transactionId);
        return booking;
    }

    // ── createHeldBooking ───────────────────────────────────────────────────────

    @Test
    void createHeldBooking_checkOutNotAfterCheckIn_throwsInvalidDateRange() {
        LocalDate date = LocalDate.of(2026, 11, 1);
        BookingRequest request = new BookingRequest(UUID.randomUUID(), date, date, "Jane",
                new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        assertThatThrownBy(() -> service.createHeldBooking(request))
                .isInstanceOf(InvalidDateRangeException.class);
    }

    @Test
    void createHeldBooking_roomNotFound_throwsRoomNotFound() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        BookingRequest request = new BookingRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                "Jane", new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        assertThatThrownBy(() -> service.createHeldBooking(request))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void createHeldBooking_available_savesHeldBookingAndNights() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
            return b;
        });
        BookingRequest request = new BookingRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                "Jane", new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        Booking result = service.createHeldBooking(request);

        assertThat(result.getStatus()).isEqualTo(BookingStatus.HELD);
        assertThat(result.getRoomId()).isEqualTo(roomId);
        verify(bookingRoomNightRepository).saveAll(any());
        verify(bookingRoomNightRepository).flush();
    }

    @Test
    void createHeldBooking_nightsConflict_throwsRoomUnavailable() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new DataIntegrityViolationException("duplicate key")).when(bookingRoomNightRepository).flush();
        BookingRequest request = new BookingRequest(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                "Jane", new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane"));

        assertThatThrownBy(() -> service.createHeldBooking(request))
                .isInstanceOf(RoomUnavailableException.class);
    }

    // ── getById ─────────────────────────────────────────────────────────────────

    @Test
    void getById_found_returnsBooking() {
        UUID id = UUID.randomUUID();
        Booking booking = booking(id, BookingStatus.HELD, null);
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));

        assertThat(service.getById(id)).isSameAs(booking);
    }

    @Test
    void getById_notFound_throwsBookingNotFound() {
        UUID id = UUID.randomUUID();
        when(bookingRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(id)).isInstanceOf(BookingNotFoundException.class);
    }

    // ── handlePaymentOutcome ────────────────────────────────────────────────────

    private static TransactionStatusEvent event(String transactionId, String outcome) {
        TransactionStatusEvent event = new TransactionStatusEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("transaction.status.updated");
        event.setOccurredAt(Instant.now());
        TransactionStatusEvent.Payload payload = new TransactionStatusEvent.Payload();
        payload.setTransactionId(transactionId);
        payload.setOutcome(outcome);
        event.setPayload(payload);
        return event;
    }

    @Test
    void handlePaymentOutcome_noMatchingBooking_throwsForRetry_andNeverWritesProcessedEvent() {
        when(bookingRepository.findByTransactionId("txn-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.handlePaymentOutcome(event("txn-missing", "AUTHORIZED")))
                .isInstanceOf(BookingNotFoundForTransactionException.class);

        verify(processedEventRepository, never()).insertIfAbsent(any());
    }

    @Test
    void handlePaymentOutcome_heldAndAuthorized_confirms() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, "txn-1");
        when(bookingRepository.findByTransactionId("txn-1")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-1", "AUTHORIZED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository).save(booking);
    }

    @Test
    void handlePaymentOutcome_heldAndDeclined_noStateChangeInThisPhase() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, "txn-2");
        when(bookingRepository.findByTransactionId("txn-2")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-2", "DECLINED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
        verify(bookingRepository, never()).save(booking);
    }

    @Test
    void handlePaymentOutcome_alreadyConfirmed_isNoOpRegardlessOfOutcome() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.CONFIRMED, "txn-4");
        when(bookingRepository.findByTransactionId("txn-4")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(1);

        service.handlePaymentOutcome(event("txn-4", "AUTHORIZED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        verify(bookingRepository, never()).save(booking);
    }

    @Test
    void initiatePayment_roomNoLongerExists_throwsRoomNotFound() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, null);
        booking.setRoomId(roomId);

        assertThatThrownBy(() -> service.initiatePayment(booking,
                new BookingRequest.CardDetails("4111111111111111", "12/28", "123", "Jane")))
                .isInstanceOf(RoomNotFoundException.class);
    }

    @Test
    void handlePaymentOutcome_alreadyProcessed_isNoOp() {
        Booking booking = booking(UUID.randomUUID(), BookingStatus.HELD, "txn-3");
        when(bookingRepository.findByTransactionId("txn-3")).thenReturn(Optional.of(booking));
        when(processedEventRepository.insertIfAbsent(any())).thenReturn(0);

        service.handlePaymentOutcome(event("txn-3", "AUTHORIZED"));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.HELD);
        verify(bookingRepository, never()).save(booking);
    }
}
