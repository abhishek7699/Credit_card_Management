package com.creditcard.booking.controller;

import com.creditcard.booking.controller.dto.RoomAvailabilityResponse;
import com.creditcard.booking.controller.dto.RoomBoardResponse;
import com.creditcard.booking.controller.dto.RoomStatusResponse;
import com.creditcard.booking.controller.dto.RoomStatusUpdateRequest;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.service.RoomAvailabilityService;
import com.creditcard.booking.service.RoomService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private final RoomAvailabilityService roomAvailabilityService;
    private final RoomService roomService;

    public RoomController(RoomAvailabilityService roomAvailabilityService, RoomService roomService) {
        this.roomAvailabilityService = roomAvailabilityService;
        this.roomService = roomService;
    }

    @GetMapping
    public List<RoomAvailabilityResponse> search(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
        return roomAvailabilityService.findAvailable(checkIn, checkOut).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/board")
    public List<RoomBoardResponse> board() {
        return roomService.getBoard();
    }

    @PatchMapping("/{id}/status")
    public RoomStatusResponse updateStatus(@PathVariable UUID id, @Valid @RequestBody RoomStatusUpdateRequest request) {
        Room room = roomService.updateStatus(id, request.status());
        return new RoomStatusResponse(room.getId(), room.getStatus());
    }

    private RoomAvailabilityResponse toResponse(Room room) {
        return new RoomAvailabilityResponse(room.getId(), room.getRoomNumber(), room.getRoomType(), room.getNightlyRate());
    }
}
