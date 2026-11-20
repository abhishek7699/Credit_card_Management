package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.PublicAvailabilityResponse;
import com.creditcard.booking.controller.dto.PublicBookingRequest;
import com.creditcard.booking.controller.dto.PublicBookingResponse;
import com.creditcard.booking.controller.dto.PublicRoomOffer;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingSource;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.repository.RoomRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Guest-facing search and booking. Every rule and price is decided here, on the
 * server: the client picks a room and dates, and the total is always nightlyRate x
 * nights. The booking itself goes through the same {@link BookingService} path as a
 * staff booking, tagged {@link BookingSource#ONLINE}, so it is paid at checkout.
 */
@Service
public class OnlineBookingService {

    private final RoomAvailabilityService roomAvailabilityService;
    private final BookingService bookingService;
    private final RoomRepository roomRepository;
    private final OnlineBookingPolicy policy;
    private final CardValidationService cardValidationService;

    public OnlineBookingService(RoomAvailabilityService roomAvailabilityService,
                                BookingService bookingService,
                                RoomRepository roomRepository,
                                OnlineBookingPolicy policy,
                                CardValidationService cardValidationService) {
        this.roomAvailabilityService = roomAvailabilityService;
        this.bookingService = bookingService;
        this.roomRepository = roomRepository;
        this.policy = policy;
        this.cardValidationService = cardValidationService;
    }

    /** Available rooms for the range, each with its total for the stay and what's included. */
    public PublicAvailabilityResponse search(LocalDate checkIn, LocalDate checkOut, LocalDate today) {
        policy.validate(checkIn, checkOut, today);
        long nights = nights(checkIn, checkOut);
        List<PublicRoomOffer> rooms = roomAvailabilityService.findAvailable(checkIn, checkOut).stream()
                .map(room -> toOffer(room, nights))
                .toList();
        return new PublicAvailabilityResponse(checkIn, checkOut, nights, rooms);
    }

    /** Books the requested room for the guest. The card is checked first, with no charge (a failed
     *  check throws CardNotVerifiedException 422, an unavailable one 503, and nothing is stored), and
     *  only its verdict, referenceId and last 4 digits are kept on the booking. The check runs before
     *  the booking's database transaction opens, so no transaction waits on Mastercard. Throws
     *  RoomUnavailableException (409) if the room was taken, the same as for a staff booking. */
    public PublicBookingResponse book(PublicBookingRequest request, LocalDate today) {
        policy.validate(request.checkIn(), request.checkOut(), today);
        CardValidationResult cardCheck = cardValidationService.validate(request.card());
        Booking booking = bookingService.createConfirmedBookingWithSource(
                request.toBookingRequest(), BookingSource.ONLINE, cardCheck);
        Room room = roomRepository.findById(booking.getRoomId())
                .orElseThrow(() -> new RoomNotFoundException(booking.getRoomId()));
        return new PublicBookingResponse(
                booking.getId(),
                booking.getStatus(),
                room.getRoomNumber(),
                room.getRoomType(),
                booking.getCheckIn(),
                booking.getCheckOut(),
                nights(booking.getCheckIn(), booking.getCheckOut()),
                booking.getRoomChargeAmount(),
                true);
    }

    private static PublicRoomOffer toOffer(Room room, long nights) {
        BigDecimal total = room.getNightlyRate().multiply(BigDecimal.valueOf(nights));
        return new PublicRoomOffer(room.getId(), room.getRoomNumber(), room.getRoomType(),
                room.getNightlyRate(), total, room.getIncluded());
    }

    private static long nights(LocalDate checkIn, LocalDate checkOut) {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }
}
