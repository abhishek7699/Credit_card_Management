package com.creditcard.reporting.repository;

import com.creditcard.reporting.domain.SettlementTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransactionReportRepository
        extends JpaRepository<SettlementTransaction, String>,
                JpaSpecificationExecutor<SettlementTransaction> {
}
