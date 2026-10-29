package com.creditcard.booking.controller;

import com.creditcard.booking.domain.Room;
import com.creditcard.booking.exception.InvalidDateRangeException;
import com.creditcard.booking.service.RoomAvailabilityService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RoomController.class)
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoomAvailabilityService roomAvailabilityService;

    @Test
    void validRange_returnsAvailableRooms() throws Exception {
        Room room = new Room();
        room.setRoomNumber("101");
        room.setRoomType("STANDARD");
        room.setNightlyRate(new BigDecimal("129.00"));
        when(roomAvailabilityService.findAvailable(any(), any())).thenReturn(List.of(room));

        mockMvc.perform(get("/api/v1/rooms")
                        .param("checkIn", "2026-11-01")
                        .param("checkOut", "2026-11-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roomNumber").value("101"))
                .andExpect(jsonPath("$[0].roomType").value("STANDARD"))
                .andExpect(jsonPath("$[0].nightlyRate").value(129.00));
    }

    @Test
    void invalidRange_returns400() throws Exception {
        LocalDate checkIn = LocalDate.of(2026, 11, 3);
        LocalDate checkOut = LocalDate.of(2026, 11, 1);
        when(roomAvailabilityService.findAvailable(checkIn, checkOut))
                .thenThrow(new InvalidDateRangeException(checkIn, checkOut));

        mockMvc.perform(get("/api/v1/rooms")
                        .param("checkIn", "2026-11-03")
                        .param("checkOut", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void noAvailableRooms_returnsEmptyList() throws Exception {
        when(roomAvailabilityService.findAvailable(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/rooms")
                        .param("checkIn", "2026-11-01")
                        .param("checkOut", "2026-11-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
