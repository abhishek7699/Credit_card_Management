package com.creditcard.booking.repository;

import com.creditcard.booking.domain.CheckoutAttemptStatus;
import com.creditcard.booking.domain.CheckoutPaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
