package com.creditcard.settlement.web.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DtoTest {

    @Test
    void batchTriggerResponse_gettersAndSetters() {
        BatchTriggerResponse r = new BatchTriggerResponse();
        Instant now = Instant.now();
        r.setBatchId("batch-001");
        r.setStatus("COMPLETED");
        r.setTriggeredBy("MANUAL");
        r.setTotalTransactionsProcessed(5);
        r.setTotalGrossAmount(new BigDecimal("500.00"));
        r.setTotalInterchangeFees(new BigDecimal("10.00"));
        r.setTotalNetAmount(new BigDecimal("490.00"));
        r.setStartedAt(now);
        r.setCompletedAt(now);

        assertThat(r.getBatchId()).isEqualTo("batch-001");
        assertThat(r.getStatus()).isEqualTo("COMPLETED");
        assertThat(r.getTriggeredBy()).isEqualTo("MANUAL");
        assertThat(r.getTotalTransactionsProcessed()).isEqualTo(5);
        assertThat(r.getTotalGrossAmount()).isEqualByComparingTo("500.00");
        assertThat(r.getTotalInterchangeFees()).isEqualByComparingTo("10.00");
        assertThat(r.getTotalNetAmount()).isEqualByComparingTo("490.00");
        assertThat(r.getStartedAt()).isEqualTo(now);
        assertThat(r.getCompletedAt()).isEqualTo(now);
    }

    @Test
    void merchantSettlementResponse_gettersAndSetters() {
        MerchantSettlementResponse r = new MerchantSettlementResponse();
        LocalDate today = LocalDate.now();
        r.setMerchantId("merchant-A");
        r.setBatchId("batch-001");
        r.setTransactionCount(3);
        r.setGrossAmount(new BigDecimal("300.00"));
        r.setTotalInterchangeFee(new BigDecimal("6.00"));
        r.setNetSettlementAmount(new BigDecimal("294.00"));
        r.setCurrencyCode("USD");
        r.setSettlementDate(today);

        assertThat(r.getMerchantId()).isEqualTo("merchant-A");
        assertThat(r.getBatchId()).isEqualTo("batch-001");
        assertThat(r.getTransactionCount()).isEqualTo(3);
        assertThat(r.getGrossAmount()).isEqualByComparingTo("300.00");
        assertThat(r.getTotalInterchangeFee()).isEqualByComparingTo("6.00");
        assertThat(r.getNetSettlementAmount()).isEqualByComparingTo("294.00");
        assertThat(r.getCurrencyCode()).isEqualTo("USD");
        assertThat(r.getSettlementDate()).isEqualTo(today);
    }
}
