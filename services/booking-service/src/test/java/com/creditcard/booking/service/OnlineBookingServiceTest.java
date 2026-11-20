package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.BookingRequest;
import com.creditcard.booking.controller.dto.PublicAvailabilityResponse;
import com.creditcard.booking.controller.dto.PublicBookingResponse;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingSource;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.exception.BookingWindowException;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OnlineBookingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    @Mock
    private RoomAvailabilityService roomAvailabilityService;
    @Mock
    private BookingService bookingService;
    @Mock
    private RoomRepository roomRepository;

    private OnlineBookingService service;

    @BeforeEach
    void setUp() {
        service = new OnlineBookingService(roomAvailabilityService, bookingService, roomRepository,
                new OnlineBookingPolicy(14, 365));
    }

    private static Room room(UUID id, String number, String type, String rate, List<String> included) {
        Room room = new Room();
        ReflectionTestUtils.setField(room, "id", id);
        room.setRoomNumber(number);
        room.setRoomType(type);
        room.setNightlyRate(new BigDecimal(rate));
        room.setIncluded(included);
        return room;
    }

    private static BookingRequest request(UUID roomId, LocalDate checkIn, LocalDate checkOut) {
        return new BookingRequest(roomId, checkIn, checkOut, "Jane Doe", "+1-555-0100",
                "jane@example.com", null, 2, 0, null, null, null);
    }

    @Test
    void search_pricesEachRoomServerSide_andListsWhatIsIncluded() {
        UUID roomId = UUID.randomUUID();
        when(roomAvailabilityService.findAvailable(TODAY.plusDays(1), TODAY.plusDays(4)))
                .thenReturn(List.of(room(roomId, "201", "DELUXE", "179.00", List.of("Wi-Fi", "Breakfast"))));

        PublicAvailabilityResponse response = service.search(TODAY.plusDays(1), TODAY.plusDays(4), TODAY);

        assertThat(response.nights()).isEqualTo(3);
        assertThat(response.rooms()).hasSize(1);
        var offer = response.rooms().get(0);
        assertThat(offer.roomId()).isEqualTo(roomId);
        assertThat(offer.roomNumber()).isEqualTo("201");
        assertThat(offer.nightlyRate()).isEqualByComparingTo("179.00");
        assertThat(offer.total()).isEqualByComparingTo("537.00"); // 3 x 179.00
        assertThat(offer.included()).containsExactly("Wi-Fi", "Breakfast");
    }

    @Test
    void search_noRoomsFree_returnsEmptyList() {
        when(roomAvailabilityService.findAvailable(TODAY, TODAY.plusDays(1))).thenReturn(List.of());

        assertThat(service.search(TODAY, TODAY.plusDays(1), TODAY).rooms()).isEmpty();
    }

    @Test
    void search_outsideOnlineRules_rejectedBeforeAnyAvailabilityQuery() {
        assertThatThrownBy(() -> service.search(TODAY, TODAY.plusDays(20), TODAY))
                .isInstanceOf(BookingWindowException.class);

        verify(roomAvailabilityService, never()).findAvailable(any(), any());
    }

    @Test
    void book_createsAnOnlineBooking_andReturnsTheServerPrice() {
        UUID roomId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        BookingRequest request = request(roomId, TODAY.plusDays(2), TODAY.plusDays(4));
        Booking booking = new Booking();
        ReflectionTestUtils.setField(booking, "id", bookingId);
        booking.setRoomId(roomId);
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setCheckIn(TODAY.plusDays(2));
        booking.setCheckOut(TODAY.plusDays(4));
        booking.setRoomChargeAmount(new BigDecimal("258.00"));
        when(bookingService.createConfirmedBookingWithSource(request, BookingSource.ONLINE)).thenReturn(booking);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room(roomId, "102", "STANDARD", "129.00", List.of("Wi-Fi"))));

        PublicBookingResponse response = service.book(request, TODAY);

        assertThat(response.bookingId()).isEqualTo(bookingId);
        assertThat(response.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(response.roomNumber()).isEqualTo("102");
        assertThat(response.roomType()).isEqualTo("STANDARD");
        assertThat(response.nights()).isEqualTo(2);
        assertThat(response.total()).isEqualByComparingTo("258.00");
        assertThat(response.payAtCheckout()).isTrue();
    }

    @Test
    void book_outsideOnlineRules_rejectedBeforeAnyBooking() {
        BookingRequest request = request(UUID.randomUUID(), TODAY.minusDays(1), TODAY.plusDays(1));

        assertThatThrownBy(() -> service.book(request, TODAY)).isInstanceOf(BookingWindowException.class);

        verify(bookingService, never()).createConfirmedBookingWithSource(any(), any());
    }

    @Test
    void book_roomGoneAfterBooking_throwsRoomNotFound() {
        UUID roomId = UUID.randomUUID();
        Booking booking = new Booking();
        booking.setRoomId(roomId);
        booking.setCheckIn(TODAY.plusDays(1));
        booking.setCheckOut(TODAY.plusDays(2));
        when(bookingService.createConfirmedBookingWithSource(eq(request(roomId, TODAY.plusDays(1), TODAY.plusDays(2))),
                eq(BookingSource.ONLINE))).thenReturn(booking);
        when(roomRepository.findById(roomId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.book(request(roomId, TODAY.plusDays(1), TODAY.plusDays(2)), TODAY))
                .isInstanceOf(RoomNotFoundException.class);
    }
}
