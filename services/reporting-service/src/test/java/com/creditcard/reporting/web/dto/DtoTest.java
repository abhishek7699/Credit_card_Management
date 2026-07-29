package com.creditcard.reporting.web.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DtoTest {

    // ── PagedResponse ──────────────────────────────────────────────────────────

    @Test
    void pagedResponse_allGettersAndSetters() {
        PagedResponse<String> response = new PagedResponse<>();
        response.setContent(List.of("a", "b"));
        response.setPage(1);
        response.setSize(10);
        response.setTotalElements(25L);
        response.setTotalPages(3);

        assertThat(response.getContent()).containsExactly("a", "b");
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(10);
        assertThat(response.getTotalElements()).isEqualTo(25L);
        assertThat(response.getTotalPages()).isEqualTo(3);
    }

    // ── TransactionResponse ────────────────────────────────────────────────────

    @Test
    void transactionResponse_allGettersAndSetters() {
        TransactionResponse r = new TransactionResponse();
        r.setTransactionId("tx-001");
        r.setCardToken("tok-abc");
        r.setMaskedCardNumber("4111-****-****-1111");
        r.setMerchantId("merchant-001");
        r.setAmount(new java.math.BigDecimal("100.00"));
        r.setCurrencyCode("USD");
        r.setStatus("SETTLED");
        r.setBatchId("batch-001");
        r.setSettlementFee(new java.math.BigDecimal("2.00"));
        r.setNetAmount(new java.math.BigDecimal("98.00"));
        r.setAuthorizedAt(java.time.Instant.parse("2026-06-13T10:00:00Z"));
        r.setSettledAt(java.time.Instant.parse("2026-06-13T23:59:00Z"));

        assertThat(r.getTransactionId()).isEqualTo("tx-001");
        assertThat(r.getCardToken()).isEqualTo("tok-abc");
        assertThat(r.getMaskedCardNumber()).isEqualTo("4111-****-****-1111");
        assertThat(r.getMerchantId()).isEqualTo("merchant-001");
        assertThat(r.getAmount()).isEqualByComparingTo("100.00");
        assertThat(r.getCurrencyCode()).isEqualTo("USD");
        assertThat(r.getStatus()).isEqualTo("SETTLED");
        assertThat(r.getBatchId()).isEqualTo("batch-001");
        assertThat(r.getSettlementFee()).isEqualByComparingTo("2.00");
        assertThat(r.getNetAmount()).isEqualByComparingTo("98.00");
        assertThat(r.getAuthorizedAt()).isEqualTo("2026-06-13T10:00:00Z");
        assertThat(r.getSettledAt()).isEqualTo("2026-06-13T23:59:00Z");
    }

    // ── MerchantSummaryResponse ────────────────────────────────────────────────

    @Test
    void merchantSummaryResponse_allGettersAndSetters() {
        MerchantSummaryResponse r = new MerchantSummaryResponse();
        r.setMerchantId("merchant-001");
        r.setTransactionCount(42L);
        r.setGrossAmount(new java.math.BigDecimal("4200.00"));
        r.setTotalInterchangeFee(new java.math.BigDecimal("84.00"));
        r.setNetSettlementAmount(new java.math.BigDecimal("4116.00"));
        r.setCurrencyCode("USD");
        r.setBatchCount(3L);
        r.setFrom(java.time.LocalDate.of(2026, 5, 14));
        r.setTo(java.time.LocalDate.of(2026, 6, 13));

        assertThat(r.getMerchantId()).isEqualTo("merchant-001");
        assertThat(r.getTransactionCount()).isEqualTo(42L);
        assertThat(r.getGrossAmount()).isEqualByComparingTo("4200.00");
        assertThat(r.getTotalInterchangeFee()).isEqualByComparingTo("84.00");
        assertThat(r.getNetSettlementAmount()).isEqualByComparingTo("4116.00");
        assertThat(r.getCurrencyCode()).isEqualTo("USD");
        assertThat(r.getBatchCount()).isEqualTo(3L);
        assertThat(r.getFrom()).isEqualTo(java.time.LocalDate.of(2026, 5, 14));
        assertThat(r.getTo()).isEqualTo(java.time.LocalDate.of(2026, 6, 13));
    }

    // ── MerchantSettlementResponse ─────────────────────────────────────────────

    @Test
    void merchantSettlementResponse_allGettersAndSetters() {
        MerchantSettlementResponse r = new MerchantSettlementResponse();
        r.setId("ms-001");
        r.setBatchId("batch-001");
        r.setMerchantId("merchant-001");
        r.setTransactionCount(10);
        r.setGrossAmount(new java.math.BigDecimal("1000.00"));
        r.setTotalInterchangeFee(new java.math.BigDecimal("20.00"));
        r.setNetSettlementAmount(new java.math.BigDecimal("980.00"));
        r.setCurrencyCode("USD");
        r.setSettlementDate(java.time.LocalDate.of(2026, 6, 13));

        assertThat(r.getId()).isEqualTo("ms-001");
        assertThat(r.getBatchId()).isEqualTo("batch-001");
        assertThat(r.getMerchantId()).isEqualTo("merchant-001");
        assertThat(r.getTransactionCount()).isEqualTo(10);
        assertThat(r.getGrossAmount()).isEqualByComparingTo("1000.00");
        assertThat(r.getTotalInterchangeFee()).isEqualByComparingTo("20.00");
        assertThat(r.getNetSettlementAmount()).isEqualByComparingTo("980.00");
        assertThat(r.getCurrencyCode()).isEqualTo("USD");
        assertThat(r.getSettlementDate()).isEqualTo(java.time.LocalDate.of(2026, 6, 13));
    }

    // ── SettlementBatchResponse ────────────────────────────────────────────────

    @Test
    void settlementBatchResponse_allGettersAndSetters() {
        SettlementBatchResponse r = new SettlementBatchResponse();
        r.setBatchId("batch-001");
        r.setStatus("COMPLETED");
        r.setTriggeredBy("SCHEDULER");
        r.setTotalTransactionsProcessed(1250);
        r.setTotalGrossAmount(new java.math.BigDecimal("312500.00"));
        r.setTotalInterchangeFees(new java.math.BigDecimal("6250.00"));
        r.setTotalNetAmount(new java.math.BigDecimal("306250.00"));
        r.setStartedAt(java.time.Instant.parse("2026-06-13T23:59:00Z"));
        r.setCompletedAt(java.time.Instant.parse("2026-06-13T23:59:47Z"));
        r.setFailureReason(null);

        assertThat(r.getBatchId()).isEqualTo("batch-001");
        assertThat(r.getStatus()).isEqualTo("COMPLETED");
        assertThat(r.getTriggeredBy()).isEqualTo("SCHEDULER");
        assertThat(r.getTotalTransactionsProcessed()).isEqualTo(1250);
        assertThat(r.getTotalGrossAmount()).isEqualByComparingTo("312500.00");
        assertThat(r.getTotalInterchangeFees()).isEqualByComparingTo("6250.00");
        assertThat(r.getTotalNetAmount()).isEqualByComparingTo("306250.00");
        assertThat(r.getStartedAt()).isEqualTo("2026-06-13T23:59:00Z");
        assertThat(r.getCompletedAt()).isEqualTo("2026-06-13T23:59:47Z");
        assertThat(r.getFailureReason()).isNull();
    }

    @Test
    void settlementBatchResponse_failedBatch_hasFailureReason() {
        SettlementBatchResponse r = new SettlementBatchResponse();
        r.setBatchId("batch-fail");
        r.setStatus("FAILED");
        r.setFailureReason("Database connection timeout");

        assertThat(r.getStatus()).isEqualTo("FAILED");
        assertThat(r.getFailureReason()).isEqualTo("Database connection timeout");
    }
}
