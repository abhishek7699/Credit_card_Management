package com.creditcard.booking.controller;

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
        when(bookingService.createHeldBooking(any())).thenReturn(booking);

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"));
    }

    @Test
    void nonexistentRoom_returns404() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(bookingService.createHeldBooking(any())).thenThrow(new RoomNotFoundException(roomId));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void roomUnavailable_returns409() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(bookingService.createHeldBooking(any())).thenThrow(
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
}
