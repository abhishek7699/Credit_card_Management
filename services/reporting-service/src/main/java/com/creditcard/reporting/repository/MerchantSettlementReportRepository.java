package com.creditcard.reporting.repository;

import com.creditcard.reporting.domain.MerchantSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MerchantSettlementReportRepository extends JpaRepository<MerchantSettlement, String> {

    List<MerchantSettlement> findByMerchantIdAndSettlementDateBetween(
            String merchantId, LocalDate from, LocalDate to);

    List<MerchantSettlement> findBySettlementDateBetween(LocalDate from, LocalDate to);

    Optional<MerchantSettlement> findByBatchIdAndMerchantId(String batchId, String merchantId);
}
