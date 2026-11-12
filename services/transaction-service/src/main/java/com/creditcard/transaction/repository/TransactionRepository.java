package com.creditcard.transaction.repository;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Conditional transition (013): changes status only while it is still {@code from}.
     * Returns 1 when this call made the change, 0 when a terminal state already won.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Transaction t SET t.status = :to, t.updatedAt = :now WHERE t.id = :id AND t.status = :from")
    int transitionStatus(@Param("id") UUID id,
                         @Param("from") TransactionStatus from,
                         @Param("to") TransactionStatus to,
                         @Param("now") Instant now);
}
