package com.creditcard.reporting.repository;

import com.creditcard.reporting.domain.SettlementBatch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementBatchReportRepository extends JpaRepository<SettlementBatch, String> {
}
