package com.creditcard.settlement.repository;

import com.creditcard.settlement.domain.SettlementBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, String> {

    Optional<SettlementBatch> findFirstByStatusOrderByStartedAtDesc(String status);
}
