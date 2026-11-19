package com.creditcard.booking.controller;

import com.creditcard.booking.controller.dto.BookingCreatedResponse;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.exception.BookingNotFoundException;
import com.creditcard.booking.exception.InvalidBookingStateException;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.exception.RoomUnavailableException;
import com.creditcard.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BookingService bookingService;

    private static Booking heldBooking(UUID roomId) {
        Booking booking = new Booking();
        booking.setRoomId(roomId);
        booking.setGuestName("Jane Doe");
        booking.setCheckIn(LocalDate.of(2026, 11, 1));
        booking.setCheckOut(LocalDate.of(2026, 11, 3));
        booking.setStatus(BookingStatus.HELD);
        return booking;
    }

    private static String bookingRequestJson(UUID roomId) {
        return """
                {
                  "roomId": "%s",
                  "checkIn": "2026-11-01",
                  "checkOut": "2026-11-03",
                  "guestName": "Jane Doe",
                  "card": {"cardNumber": "4111111111111111", "expiry": "12/28", "cvv": "123", "cardholderName": "Jane Doe"}
                }
                """.formatted(roomId);
    }

    @Test
    void validRequest_returns201Held() throws Exception {
        UUID roomId = UUID.randomUUID();
        Booking booking = heldBooking(roomId);
        when(bookingService.holdBooking(isNull(), any()))
                .thenReturn(new BookingService.CreateOutcome(
                        new BookingCreatedResponse(booking.getId(), booking.getStatus()), booking));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void nonexistentRoom_returns404() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(bookingService.holdBooking(isNull(), any())).thenThrow(new RoomNotFoundException(roomId));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void roomUnavailable_returns409() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(bookingService.holdBooking(isNull(), any())).thenThrow(
                new RoomUnavailableException(roomId, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3)));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void missingGuestName_returns400() throws Exception {
        UUID roomId = UUID.randomUUID();
        String json = """
                {
                  "roomId": "%s",
                  "checkIn": "2026-11-01",
                  "checkOut": "2026-11-03",
                  "guestName": "",
                  "card": {"cardNumber": "4111111111111111", "expiry": "12/28", "cvv": "123", "cardholderName": "Jane Doe"}
                }
                """.formatted(roomId);

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getById_found_returnsDetail() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Booking booking = heldBooking(UUID.randomUUID());
        when(bookingService.getById(bookingId)).thenReturn(booking);

        mockMvc.perform(get("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestName").value("Jane Doe"))
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingService.getById(bookingId)).thenThrow(new BookingNotFoundException(bookingId));

        mockMvc.perform(get("/api/v1/bookings/" + bookingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void checkIn_succeeds_returns200CheckedIn() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Booking checkedIn = heldBooking(UUID.randomUUID());
        checkedIn.setStatus(BookingStatus.CHECKED_IN);
        when(bookingService.checkIn(bookingId)).thenReturn(checkedIn);

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-in"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_IN"));
    }

    @Test
    void checkIn_invalidState_returns409() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingService.checkIn(bookingId))
                .thenThrow(new InvalidBookingStateException(bookingId, "booking must be CONFIRMED"));

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-in"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void checkIn_bookingNotFound_returns404() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingService.checkIn(bookingId)).thenThrow(new BookingNotFoundException(bookingId));

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-in"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void checkOut_succeeds_returns200CheckedOut() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Booking checkedOut = heldBooking(UUID.randomUUID());
        checkedOut.setStatus(BookingStatus.CHECKED_OUT);
        when(bookingService.checkOut(bookingId)).thenReturn(checkedOut);

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));
    }

    @Test
    void checkOut_invalidState_returns409() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingService.checkOut(bookingId))
                .thenThrow(new InvalidBookingStateException(bookingId, "booking must be CHECKED_IN"));

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-out"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── Idempotency-Key header ────────────────────────────────────────────────

    private static BookingService.CreateOutcome newlyHeld(Booking booking) {
        return new BookingService.CreateOutcome(
                new BookingCreatedResponse(booking.getId(), booking.getStatus()), booking);
    }

    @Test
    void idempotencyKeyHeader_isPassedToService() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(bookingService.holdBooking(anyString(), any())).thenReturn(newlyHeld(heldBooking(roomId)));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", "front-desk-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isCreated());

        verify(bookingService).holdBooking(eq("front-desk-42"), any());
        verify(bookingService).initiatePayment(any(), any());
    }

    @Test
    void blankIdempotencyKeyHeader_behavesAsNoHeader() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(bookingService.holdBooking(isNull(), any())).thenReturn(newlyHeld(heldBooking(roomId)));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isCreated());

        verify(bookingService).holdBooking(isNull(), any());
    }

    @Test
    void replayedKey_returnsStoredResultAndDoesNotInitiatePaymentAgain() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID storedId = UUID.randomUUID();
        when(bookingService.holdBooking(eq("front-desk-42"), any())).thenReturn(
                new BookingService.CreateOutcome(new BookingCreatedResponse(storedId, BookingStatus.HELD), null));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", "front-desk-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(storedId.toString()))
                .andExpect(jsonPath("$.status").value("HELD"));

        verify(bookingService, never()).initiatePayment(any(), any());
    }

    @Test
    void idempotencyKeyLongerThan255Characters_returns400WithoutCallingService() throws Exception {
        UUID roomId = UUID.randomUUID();
        String tooLong = "k".repeat(256);

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", tooLong)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verify(bookingService, never()).holdBooking(any(), any());
    }

    @Test
    void idempotencyKeyOfExactly255Characters_isAccepted() throws Exception {
        UUID roomId = UUID.randomUUID();
        String atLimit = "k".repeat(255);
        when(bookingService.holdBooking(eq(atLimit), any())).thenReturn(newlyHeld(heldBooking(roomId)));

        mockMvc.perform(post("/api/v1/bookings")
                        .header("Idempotency-Key", atLimit)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isCreated());
    }
}
