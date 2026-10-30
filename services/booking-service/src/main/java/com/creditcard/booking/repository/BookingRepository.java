package com.creditcard.booking.repository;

import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByTransactionId(String transactionId);

    /** Used by HoldExpiryService (Phase 5) to find abandoned HELD bookings. */
    List<Booking> findByStatusAndCreatedAtBefore(BookingStatus status, Instant cutoff);

    /** At most one CHECKED_IN booking can exist per room at a time, by construction
     *  (a room must pass back through VACANT_CLEAN before it can be occupied again) —
     *  used by the room board to find the current guest for an OCCUPIED room. */
    Optional<Booking> findByRoomIdAndStatus(UUID roomId, BookingStatus status);

    List<Booking> findByStatus(BookingStatus status);

    /**
     * Used by the room board (frontend-mvp layer) to surface a room's
     * arriving-today booking for check-in — scoped to the booking's check-in
     * date, not its confirmation date, so a booking confirmed weeks ahead for a
     * future stay doesn't show as actionable before its stay actually begins.
     *
     * <p>Explicit JPQL rather than a derived method name: Spring Data's method-name
     * parser reads a trailing "...CheckIn" as the property "check" followed by the
     * reserved {@code In} (collection) keyword — not the literal {@code checkIn}
     * field — and fails with a PropertyReferenceException on "check" not existing.
     */
    @Query("SELECT b FROM Booking b WHERE b.status = :status AND b.checkIn = :checkIn")
    List<Booking> findByStatusAndCheckIn(@Param("status") BookingStatus status, @Param("checkIn") LocalDate checkIn);

    /**
     * Concurrency-safe state transition (research.md §2, layer 2b): the WHERE clause's
     * expected-current-status is the entire guard — zero rows affected means the
     * precondition didn't hold at the instant of the update, whether it never held or
     * a concurrent request changed it first. Either way, the caller treats it as a
     * rejection, never as a silent no-op or a retry.
     *
     * <p>{@code clearAutomatically = true}: this is a bulk JPQL update, which bypasses
     * the persistence context — without clearing it, an entity already loaded earlier
     * in the same transaction would keep stale in-memory state after this runs.
     * Callers that need the post-update row re-fetch it rather than patch the stale
     * managed instance by hand.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Booking b SET b.status = :newStatus WHERE b.id = :id AND b.status = :expected")
    int updateStatusIfCurrent(@Param("id") UUID id,
                              @Param("newStatus") BookingStatus newStatus,
                              @Param("expected") BookingStatus expected);
}
