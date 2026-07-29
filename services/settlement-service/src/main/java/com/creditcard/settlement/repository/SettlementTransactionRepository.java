package com.creditcard.settlement.repository;

import com.creditcard.settlement.domain.SettlementTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementTransactionRepository extends JpaRepository<SettlementTransaction, UUID> {

    List<SettlementTransaction> findByStatus(String status);
}
