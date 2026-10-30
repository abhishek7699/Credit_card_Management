package com.creditcard.booking.repository;

import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutPaymentAttemptRepository extends JpaRepository<CheckoutPaymentAttempt, UUID> {

    /** Fallback lookup when a Kafka outcome's transactionId misses on
     *  BookingRepository#findByTransactionId -- it belongs to a checkout attempt,
     *  possibly one already EXPIRED by the sweep (data-model.md Key Design
     *  Decision #3). */
    Optional<CheckoutPaymentAttempt> findByTransactionId(String transactionId);

    /** Balance calculation (RoomService bulk variant, US2) and the "one attempt in
     *  flight" 409 check (BookingService#checkOut, US3) both need the set of a
     *  booking's attempts in specific statuses. */
    List<CheckoutPaymentAttempt> findByBookingIdInAndStatusIn(List<UUID> bookingIds, List<CheckoutAttemptStatus> statuses);

    /** Used by HoldExpiryService's second scheduled sweep (US3) to find PENDING
     *  attempts older than the same hold-timeout window already used for HELD
     *  bookings. */
    List<CheckoutPaymentAttempt> findByStatusAndCreatedAtBefore(CheckoutAttemptStatus status, Instant cutoff);

    /**
     * Concurrency-safe state transition, same pattern as BookingRepository/
     * RoomRepository's own (research.md §2): guards every attempt transition
     * (PENDING -> AUTHORIZED/DECLINED/FAILED/EXPIRED, EXPIRED -> LATE_AUTHORIZED) so a
     * late Kafka outcome and the hold-expiry sweep racing on the same attempt can never
     * both apply — whichever runs first wins, the other sees 0 rows affected and no-ops.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE CheckoutPaymentAttempt c SET c.status = :newStatus WHERE c.id = :id AND c.status = :expected")
    int updateStatusIfCurrent(@Param("id") UUID id,
                              @Param("newStatus") CheckoutAttemptStatus newStatus,
                              @Param("expected") CheckoutAttemptStatus expected);
}
