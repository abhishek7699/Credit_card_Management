package com.creditcard.frauddetection.repository;

import com.creditcard.frauddetection.domain.TransactionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRecordRepository extends JpaRepository<TransactionRecord, UUID> {

    long countByCardTokenAndOccurredAtAfter(String cardToken, Instant cutoff);

    @Query("SELECT AVG(t.amount) FROM TransactionRecord t WHERE t.cardToken = :cardToken AND t.occurredAt > :cutoff")
    Optional<BigDecimal> findAverageAmountByCardTokenAndOccurredAtAfter(
            @Param("cardToken") String cardToken,
            @Param("cutoff") Instant cutoff);
}
