package com.creditcard.booking.service;

import com.creditcard.booking.controller.dto.RoomBoardResponse;
import com.creditcard.booking.controller.dto.RoomBoardResponse.RoomBoardEntry;
import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import com.creditcard.booking.domain.Charge;
import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import com.creditcard.booking.domain.Room;
import com.creditcard.booking.domain.RoomStatus;
import com.creditcard.booking.exception.InvalidRoomStatusTransitionException;
import com.creditcard.booking.exception.RoomNotFoundException;
import com.creditcard.booking.repository.BookingRepository;
import com.creditcard.booking.repository.ChargeRepository;
import com.creditcard.booking.repository.CheckoutPaymentAttemptRepository;
import com.creditcard.booking.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Room-status transitions (front-desk or housekeeping) and the room board — the
 *  staff-facing read view across all of layer 2b. Check-in/check-out live in
 *  BookingService — see its own Javadoc. */
@Service
public class RoomService {

    /**
     * FR-010: the full set of allowed room-status transitions. OCCUPIED has no entry
     * here deliberately — it is reached only via check-in and left only via check-out
     * (to DIRTY); this endpoint never accepts OCCUPIED as a target, and never accepts
     * a transition out of it either (data-model.md's state machine).
     */
    private static final Map<RoomStatus, Set<RoomStatus>> ALLOWED_TRANSITIONS = Map.of(
            RoomStatus.DIRTY, Set.of(RoomStatus.VACANT_CLEAN, RoomStatus.MAINTENANCE),
            RoomStatus.VACANT_CLEAN, Set.of(RoomStatus.MAINTENANCE),
            RoomStatus.MAINTENANCE, Set.of(RoomStatus.VACANT_CLEAN)
    );

    /** Statuses that count as "paid" toward a booking's balance due — same list as
     *  BookingService's own (data-model.md's balance formula); duplicated rather than
     *  shared on purpose, since RoomService must not gain a BookingService dependency
     *  (plan.md Key Design Decision #5). */
    private static final List<CheckoutAttemptStatus> PAID_CHECKOUT_STATUSES =
            List.of(CheckoutAttemptStatus.AUTHORIZED, CheckoutAttemptStatus.LATE_AUTHORIZED);

    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;
    private final ChargeRepository chargeRepository;
    private final CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository;

    public RoomService(RoomRepository roomRepository,
                        BookingRepository bookingRepository,
                        ChargeRepository chargeRepository,
                        CheckoutPaymentAttemptRepository checkoutPaymentAttemptRepository) {
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
        this.chargeRepository = chargeRepository;
        this.checkoutPaymentAttemptRepository = checkoutPaymentAttemptRepository;
    }

    /**
     * FR-010/FR-011: validates the requested (current, target) pair against
     * ALLOWED_TRANSITIONS, then applies it via the same conditional-update pattern as
     * check-in/check-out (research.md §2) using the just-read current status as the
     * expected value — a concurrent change between the read and the update surfaces as
     * the exact same rejection (plan.md Key Design Decision #4), never a silently
     * wrong write.
     */
    @Transactional
    public Room updateStatus(UUID roomId, RoomStatus requested) {
        Room room = getRoomOrThrow(roomId);
        RoomStatus current = room.getStatus();

        if (!ALLOWED_TRANSITIONS.getOrDefault(current, Set.of()).contains(requested)) {
            throw new InvalidRoomStatusTransitionException(roomId, current, requested);
        }

        int updated = roomRepository.updateStatusIfCurrent(roomId, requested, current);
        if (updated == 0) {
            throw new InvalidRoomStatusTransitionException(roomId, current, requested);
        }

        return getRoomOrThrow(roomId);
    }

    /** Shared by both the pre-update read and the post-update re-fetch in updateStatus
     *  — one lambda, not two, so the not-found test case covers both call sites. */
    private Room getRoomOrThrow(UUID roomId) {
        return roomRepository.findById(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
    }

    /**
     * FR-012/FR-013: every room, grouped by floor and ordered by position, with the
     * current guest's name/dates present only when OCCUPIED. Three queries, joined in
     * memory by roomId (research.md §3) — at most one CHECKED_IN booking can exist per
     * room at a time, by construction. The arriving-today map (frontend-mvp layer,
     * specs/010-frontend-mvp) lets any staff session check in or check out a room
     * directly from the board, not only one it personally created or saw earlier.
     */
    public List<RoomBoardResponse> getBoard() {
        List<Room> rooms = roomRepository.findAllByOrderByFloorAscPositionAsc();
        Map<UUID, Booking> occupantByRoomId = bookingRepository.findByStatus(BookingStatus.CHECKED_IN).stream()
                .collect(Collectors.toMap(Booking::getRoomId, Function.identity()));
        Map<UUID, Booking> arrivingByRoomId = bookingRepository
                .findByStatusAndCheckIn(BookingStatus.CONFIRMED, LocalDate.now()).stream()
                .collect(Collectors.toMap(Booking::getRoomId, Function.identity()));
        Map<UUID, Boolean> hasBalanceDueByBookingId = hasBalanceDueByBookingId(
                occupantByRoomId.values().stream().map(Booking::getId).toList());

        List<RoomBoardResponse> board = new ArrayList<>();
        Integer currentFloor = null;
        List<RoomBoardEntry> currentFloorRooms = null;
        for (Room room : rooms) {
            if (!room.getFloor().equals(currentFloor)) {
                currentFloor = room.getFloor();
                currentFloorRooms = new ArrayList<>();
                board.add(new RoomBoardResponse(currentFloor, currentFloorRooms));
            }
            Booking occupant = occupantByRoomId.get(room.getId());
            Boolean hasBalanceDue = occupant != null ? hasBalanceDueByBookingId.getOrDefault(occupant.getId(), false) : null;
            currentFloorRooms.add(toEntry(room, occupant, arrivingByRoomId.get(room.getId()), hasBalanceDue));
        }
        return board;
    }

    /**
     * FR-006 (layer 2c), one bulk pass per board refresh, not N+1 (plan.md Key Design
     * Decision #5): sums each occupied booking's charges minus its already-paid
     * checkout attempts. {@code roomChargeAmount} never enters this calculation — it
     * cancels out of the balance formula identically regardless of its value (see
     * BookingService#getBalanceDue's own javadoc), so unlike that method this bulk
     * variant never needs a per-booking Room lookup, legacy or not.
     */
    private Map<UUID, Boolean> hasBalanceDueByBookingId(List<UUID> occupiedBookingIds) {
        if (occupiedBookingIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, BigDecimal> chargesByBookingId = chargeRepository.findByBookingIdIn(occupiedBookingIds).stream()
                .collect(Collectors.groupingBy(Charge::getBookingId,
                        Collectors.reducing(BigDecimal.ZERO, Charge::getAmount, BigDecimal::add)));
        Map<UUID, BigDecimal> paidByBookingId = checkoutPaymentAttemptRepository
                .findByBookingIdInAndStatusIn(occupiedBookingIds, PAID_CHECKOUT_STATUSES).stream()
                .collect(Collectors.groupingBy(CheckoutPaymentAttempt::getBookingId,
                        Collectors.reducing(BigDecimal.ZERO, CheckoutPaymentAttempt::getAmount, BigDecimal::add)));
        return occupiedBookingIds.stream().collect(Collectors.toMap(Function.identity(), bookingId -> {
            BigDecimal charges = chargesByBookingId.getOrDefault(bookingId, BigDecimal.ZERO);
            BigDecimal paid = paidByBookingId.getOrDefault(bookingId, BigDecimal.ZERO);
            return charges.subtract(paid).compareTo(BigDecimal.ZERO) > 0;
        }));
    }

    private static RoomBoardEntry toEntry(Room room, Booking occupant, Booking arriving, Boolean hasBalanceDue) {
        return new RoomBoardEntry(
                room.getId(),
                room.getRoomNumber(),
                room.getPosition(),
                room.getStatus(),
                occupant != null ? occupant.getGuestName() : null,
                occupant != null ? occupant.getCheckIn() : null,
                occupant != null ? occupant.getCheckOut() : null,
                occupant != null ? occupant.getId() : null,
                arriving != null ? arriving.getId() : null,
                arriving != null ? arriving.getGuestName() : null,
                hasBalanceDue);
    }
}
