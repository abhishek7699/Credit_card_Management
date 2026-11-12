package com.creditcard.booking.controller;

import com.creditcard.booking.controller.dto.CardDetails;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Charge;
import com.creditcard.booking.domain.ChargeType;
import com.creditcard.booking.exception.BookingNotFoundException;
import com.creditcard.booking.exception.CardRequiredException;
import com.creditcard.booking.exception.InvalidBookingStateException;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.exception.RoomUnavailableException;
import com.creditcard.booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    private static Booking confirmedBooking(UUID roomId) {
        Booking booking = new Booking();
        booking.setRoomId(roomId);
        booking.setGuestName("Jane Doe");
        booking.setGuestPhone("+1-555-0100");
        booking.setCheckIn(LocalDate.of(2026, 11, 1));
        booking.setCheckOut(LocalDate.of(2026, 11, 3));
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setRoomChargeAmount(new BigDecimal("200.00"));
        return booking;
    }

    private static String bookingRequestJson(UUID roomId) {
        return """
                {
                  "roomId": "%s",
                  "checkIn": "2026-11-01",
                  "checkOut": "2026-11-03",
                  "guestName": "Jane Doe",
                  "guestPhone": "+1-555-0100"
                }
                """.formatted(roomId);
    }

    @Test
    void validRequest_returns201Confirmed() throws Exception {
        UUID roomId = UUID.randomUUID();
        Booking booking = confirmedBooking(roomId);
        when(bookingService.createConfirmedBooking(any())).thenReturn(booking);

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void nonexistentRoom_returns404() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(bookingService.createConfirmedBooking(any())).thenThrow(new RoomNotFoundException(roomId));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingRequestJson(roomId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void roomUnavailable_returns409() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(bookingService.createConfirmedBooking(any())).thenThrow(
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
                  "guestPhone": "+1-555-0100"
                }
                """.formatted(roomId);

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingGuestPhone_returns400() throws Exception {
        UUID roomId = UUID.randomUUID();
        String json = """
                {
                  "roomId": "%s",
                  "checkIn": "2026-11-01",
                  "checkOut": "2026-11-03",
                  "guestName": "Jane Doe",
                  "guestPhone": ""
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
        Booking booking = confirmedBooking(UUID.randomUUID());
        when(bookingService.getById(bookingId)).thenReturn(booking);

        mockMvc.perform(get("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestName").value("Jane Doe"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
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
        Booking checkedIn = confirmedBooking(UUID.randomUUID());
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
    void checkOut_noBody_zeroBalance_returns200CheckedOut() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Booking checkedOut = confirmedBooking(UUID.randomUUID());
        checkedOut.setStatus(BookingStatus.CHECKED_OUT);
        when(bookingService.checkOut(eq(bookingId), isNull())).thenReturn(checkedOut);

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));
    }

    @Test
    void checkOut_emptyBody_zeroBalance_returns200CheckedOut() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Booking checkedOut = confirmedBooking(UUID.randomUUID());
        checkedOut.setStatus(BookingStatus.CHECKED_OUT);
        when(bookingService.checkOut(eq(bookingId), isNull())).thenReturn(checkedOut);

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));
    }

    @Test
    void checkOut_withCard_balanceDue_returns200CheckoutPending() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Booking pending = confirmedBooking(UUID.randomUUID());
        pending.setStatus(BookingStatus.CHECKOUT_PENDING);
        when(bookingService.checkOut(eq(bookingId), any(CardDetails.class))).thenReturn(pending);

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-out")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"card": {"cardNumber": "4111111111111111", "expiry": "12/30",
                                          "cvv": "123", "cardholderName": "Jane Doe"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHECKOUT_PENDING"));
    }

    @Test
    void checkOut_invalidState_returns409() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingService.checkOut(eq(bookingId), isNull()))
                .thenThrow(new InvalidBookingStateException(bookingId, "booking must be CHECKED_IN"));

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-out"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void checkOut_balanceDueNoCard_returns400() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingService.checkOut(eq(bookingId), isNull()))
                .thenThrow(new CardRequiredException(bookingId, new BigDecimal("24.50")));

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/check-out"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    // ── charges (layer 2c) ──────────────────────────────────────────────────────

    private static Charge charge(UUID bookingId, ChargeType type, String description, String amount) {
        Charge charge = new Charge();
        ReflectionTestUtils.setField(charge, "id", UUID.randomUUID());
        charge.setBookingId(bookingId);
        charge.setType(type);
        charge.setDescription(description);
        charge.setAmount(new BigDecimal(amount));
        ReflectionTestUtils.setField(charge, "createdAt", Instant.parse("2026-10-30T18:04:11Z"));
        return charge;
    }

    @Test
    void addCharge_validRequest_returns201() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Charge saved = charge(bookingId, ChargeType.ROOM_SERVICE, "Club sandwich", "18.50");
        when(bookingService.addCharge(eq(bookingId), any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ROOM_SERVICE", "description": "Club sandwich", "amount": 18.50}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("ROOM_SERVICE"))
                .andExpect(jsonPath("$.description").value("Club sandwich"))
                .andExpect(jsonPath("$.amount").value(18.50));
    }

    @Test
    void addCharge_bookingNotCheckedIn_returns409() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingService.addCharge(eq(bookingId), any()))
                .thenThrow(new InvalidBookingStateException(bookingId, "booking must be CHECKED_IN to add a charge"));

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ROOM_SERVICE", "description": "Club sandwich", "amount": 18.50}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void addCharge_bookingNotFound_returns404() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(bookingService.addCharge(eq(bookingId), any())).thenThrow(new BookingNotFoundException(bookingId));

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ROOM_SERVICE", "description": "Club sandwich", "amount": 18.50}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void addCharge_missingDescription_returns400() throws Exception {
        UUID bookingId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/bookings/" + bookingId + "/charges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ROOM_SERVICE", "description": "", "amount": 18.50}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getCharges_returnsOrderedList() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Charge first = charge(bookingId, ChargeType.ROOM_SERVICE, "Club sandwich", "18.50");
        Charge second = charge(bookingId, ChargeType.MINIBAR, "Two sodas", "6.00");
        when(bookingService.getCharges(bookingId)).thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/v1/bookings/" + bookingId + "/charges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ROOM_SERVICE"))
                .andExpect(jsonPath("$[1].type").value("MINIBAR"));
    }

    @Test
    void getById_includesChargesAndBalanceDue() throws Exception {
        UUID bookingId = UUID.randomUUID();
        Booking booking = confirmedBooking(UUID.randomUUID());
        ReflectionTestUtils.setField(booking, "id", bookingId);
        booking.setStatus(BookingStatus.CHECKED_IN);
        Charge onlyCharge = charge(bookingId, ChargeType.MINIBAR, "Two sodas", "6.00");
        when(bookingService.getById(bookingId)).thenReturn(booking);
        when(bookingService.getCharges(bookingId)).thenReturn(List.of(onlyCharge));
        when(bookingService.getBalanceDue(booking)).thenReturn(new BigDecimal("6.00"));

        mockMvc.perform(get("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.charges[0].description").value("Two sodas"))
                .andExpect(jsonPath("$.balanceDue").value(6.00));
    }
}
