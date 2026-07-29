package com.creditcard.settlement.repository;

import com.creditcard.settlement.domain.MerchantSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface MerchantSettlementRepository extends JpaRepository<MerchantSettlement, UUID> {

    Optional<MerchantSettlement> findByBatchIdAndMerchantId(String batchId, String merchantId);

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO merchant_settlements
                (id, batch_id, merchant_id, transaction_count, gross_amount,
                 total_interchange_fee, net_settlement_amount, currency_code, settlement_date, created_at)
            VALUES (gen_random_uuid(), :batchId, :merchantId, :txCount, :gross, :fee, :net, 'USD', :date, NOW())
            ON CONFLICT (batch_id, merchant_id)
            DO UPDATE SET
                transaction_count      = EXCLUDED.transaction_count,
                gross_amount           = EXCLUDED.gross_amount,
                total_interchange_fee  = EXCLUDED.total_interchange_fee,
                net_settlement_amount  = EXCLUDED.net_settlement_amount
            """, nativeQuery = true)
    void upsertMerchantSettlement(
            @Param("batchId") String batchId,
            @Param("merchantId") String merchantId,
            @Param("txCount") int txCount,
            @Param("gross") BigDecimal gross,
            @Param("fee") BigDecimal fee,
            @Param("net") BigDecimal net,
            @Param("date") LocalDate date);
}
