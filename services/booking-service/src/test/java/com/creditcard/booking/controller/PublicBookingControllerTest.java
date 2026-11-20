package com.creditcard.booking.controller;

import com.creditcard.booking.controller.dto.PublicAvailabilityResponse;
import com.creditcard.booking.controller.dto.PublicBookingResponse;
import com.creditcard.booking.controller.dto.PublicRoomOffer;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.exception.BookingWindowException;
import com.creditcard.booking.service.OnlineBookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicBookingController.class)
class PublicBookingControllerTest {

    private static final String VALID_BOOKING = """
            {"roomId":"%s","checkIn":"2030-01-10","checkOut":"2030-01-12",
             "guestName":"Jane Doe","guestPhone":"+1-555-0100"}
            """.formatted(UUID.randomUUID());

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OnlineBookingService onlineBookingService;

    @Test
    void availability_returnsRoomsWithTotalsAndIncluded() throws Exception {
        PublicRoomOffer offer = new PublicRoomOffer(UUID.randomUUID(), "201", "DELUXE",
                new BigDecimal("179.00"), new BigDecimal("358.00"), List.of("Wi-Fi", "Breakfast"));
        when(onlineBookingService.search(eq(LocalDate.of(2030, 1, 10)), eq(LocalDate.of(2030, 1, 12)), any()))
                .thenReturn(new PublicAvailabilityResponse(LocalDate.of(2030, 1, 10), LocalDate.of(2030, 1, 12), 2,
                        List.of(offer)));

        mockMvc.perform(get("/api/v1/public/availability")
                        .param("checkIn", "2030-01-10")
                        .param("checkOut", "2030-01-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nights").value(2))
                .andExpect(jsonPath("$.rooms[0].roomNumber").value("201"))
                .andExpect(jsonPath("$.rooms[0].total").value(358.00))
                .andExpect(jsonPath("$.rooms[0].included[1]").value("Breakfast"));
    }

    @Test
    void availability_outsideOnlineRules_returns400WithTheReason() throws Exception {
        when(onlineBookingService.search(any(), any(), any()))
                .thenThrow(new BookingWindowException("a stay can be at most 14 nights (requested 20)"));

        mockMvc.perform(get("/api/v1/public/availability")
                        .param("checkIn", "2030-01-10")
                        .param("checkOut", "2030-01-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("a stay can be at most 14 nights (requested 20)"));
    }

    @Test
    void book_returns201WithTheReferenceAndPayAtCheckout() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(onlineBookingService.book(any(), any())).thenReturn(new PublicBookingResponse(bookingId,
                BookingStatus.CONFIRMED, "102", "STANDARD", LocalDate.of(2030, 1, 10), LocalDate.of(2030, 1, 12),
                2, new BigDecimal("258.00"), true));

        mockMvc.perform(post("/api/v1/public/bookings").contentType(APPLICATION_JSON).content(VALID_BOOKING))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.total").value(258.00))
                .andExpect(jsonPath("$.payAtCheckout").value(true));
    }

    @Test
    void book_missingGuestName_returns400() throws Exception {
        String body = """
                {"roomId":"%s","checkIn":"2030-01-10","checkOut":"2030-01-12","guestPhone":"+1-555-0100"}
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/public/bookings").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
