package com.creditcard.booking.repository;

import com.creditcard.booking.domain.Booking;
import com.creditcard.booking.domain.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByTransactionId(String transactionId);

    /** Used by HoldExpiryService (Phase 5) to find abandoned HELD bookings. */
    List<Booking> findByStatusAndCreatedAtBefore(BookingStatus status, Instant cutoff);
}
