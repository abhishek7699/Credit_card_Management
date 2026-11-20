package com.creditcard.booking.repository;

import com.creditcard.booking.domain.UnmatchedPaymentOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UnmatchedPaymentOutcomeRepository extends JpaRepository<UnmatchedPaymentOutcome, String> {

    /** Parks an outcome. Returns 0 when this transactionId is already parked (a Kafka redelivery). */
    @Modifying
    @Query(value = "INSERT INTO unmatched_payment_outcomes (transaction_id, event_id, outcome, received_at) "
                 + "VALUES (:transactionId, :eventId, :outcome, :receivedAt) ON CONFLICT DO NOTHING",
           nativeQuery = true)
    int insertIfAbsent(@Param("transactionId") String transactionId,
                       @Param("eventId") String eventId,
                       @Param("outcome") String outcome,
                       @Param("receivedAt") Instant receivedAt);

    /** Parked outcomes received before the cutoff: still unmatched past the alert threshold. */
    List<UnmatchedPaymentOutcome> findByReceivedAtBefore(Instant cutoff);
}
