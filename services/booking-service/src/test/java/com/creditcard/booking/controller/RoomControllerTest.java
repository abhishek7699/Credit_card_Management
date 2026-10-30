package com.creditcard.booking.controller;

import com.creditcard.booking.controller.dto.RoomBoardResponse;
import com.creditcard.booking.controller.dto.RoomBoardResponse.RoomBoardEntry;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.exception.InvalidDateRangeException;
import com.creditcard.booking.exception.InvalidRoomStatusTransitionException;
import com.creditcard.booking.service.RoomAvailabilityService;
import com.creditcard.booking.service.RoomService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@WebMvcTest(RoomController.class)
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RoomAvailabilityService roomAvailabilityService;

    @MockBean
    private RoomService roomService;

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

    @Test
    void board_returnsRoomsGroupedByFloor() throws Exception {
        RoomBoardEntry occupied = new RoomBoardEntry(UUID.randomUUID(), "101", 1, RoomStatus.OCCUPIED,
                "Jane Doe", LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 3));
        RoomBoardEntry vacant = new RoomBoardEntry(UUID.randomUUID(), "102", 2, RoomStatus.VACANT_CLEAN,
                null, null, null);
        when(roomService.getBoard()).thenReturn(List.of(new RoomBoardResponse(1, List.of(occupied, vacant))));

        mockMvc.perform(get("/api/v1/rooms/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].floor").value(1))
                .andExpect(jsonPath("$[0].rooms[0].status").value("OCCUPIED"))
                .andExpect(jsonPath("$[0].rooms[0].guestName").value("Jane Doe"))
                .andExpect(jsonPath("$[0].rooms[1].status").value("VACANT_CLEAN"))
                .andExpect(jsonPath("$[0].rooms[1].guestName").value(nullValue()));
    }

    @Test
    void updateStatus_allowedTransition_returns200() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(roomService.updateStatus(eq(roomId), eq(RoomStatus.VACANT_CLEAN))).thenAnswer(invocation -> {
            Room r = new Room();
            r.setStatus(RoomStatus.VACANT_CLEAN);
            return r;
        });

        mockMvc.perform(patch("/api/v1/rooms/{id}/status", roomId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"VACANT_CLEAN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VACANT_CLEAN"));
    }

    @Test
    void updateStatus_disallowedTransition_returns409() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(roomService.updateStatus(eq(roomId), eq(RoomStatus.OCCUPIED)))
                .thenThrow(new InvalidRoomStatusTransitionException(roomId, RoomStatus.VACANT_CLEAN, RoomStatus.OCCUPIED));

        mockMvc.perform(patch("/api/v1/rooms/{id}/status", roomId)
                        .contentType(APPLICATION_JSON)
                        .content("{\"status\":\"OCCUPIED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void updateStatus_missingStatus_returns400() throws Exception {
        UUID roomId = UUID.randomUUID();

        mockMvc.perform(patch("/api/v1/rooms/{id}/status", roomId)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
