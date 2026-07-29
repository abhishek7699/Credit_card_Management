package com.creditcard.settlement.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementBatchTest {

    @Test
    void onCreate_setsStartedAt() throws Exception {
        SettlementBatch batch = new SettlementBatch();
        assertThat(batch.getStartedAt()).isNull();
        Method onCreate = SettlementBatch.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(batch);
        assertThat(batch.getStartedAt()).isNotNull();
    }

    @Test
    void defaultStatus_isRunning() {
        SettlementBatch batch = new SettlementBatch();
        assertThat(batch.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void defaultTotals_areZero() {
        SettlementBatch batch = new SettlementBatch();
        assertThat(batch.getTotalTransactionsProcessed()).isZero();
        assertThat(batch.getTotalGrossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(batch.getTotalInterchangeFees()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(batch.getTotalNetAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void gettersAndSetters() {
        Instant now = Instant.now();

        SettlementBatch batch = new SettlementBatch();
        batch.setBatchId("batch-001");
        batch.setTriggeredBy("MANUAL");
        batch.setStatus("COMPLETED");
        batch.setTotalTransactionsProcessed(42);
        batch.setTotalGrossAmount(new BigDecimal("10500.00"));
        batch.setTotalInterchangeFees(new BigDecimal("210.00"));
        batch.setTotalNetAmount(new BigDecimal("10290.00"));
        batch.setStartedAt(now);
        batch.setCompletedAt(now);
        batch.setFailureReason("some error");

        assertThat(batch.getBatchId()).isEqualTo("batch-001");
        assertThat(batch.getTriggeredBy()).isEqualTo("MANUAL");
        assertThat(batch.getStatus()).isEqualTo("COMPLETED");
        assertThat(batch.getTotalTransactionsProcessed()).isEqualTo(42);
        assertThat(batch.getTotalGrossAmount()).isEqualByComparingTo("10500.00");
        assertThat(batch.getTotalInterchangeFees()).isEqualByComparingTo("210.00");
        assertThat(batch.getTotalNetAmount()).isEqualByComparingTo("10290.00");
        assertThat(batch.getStartedAt()).isEqualTo(now);
        assertThat(batch.getCompletedAt()).isEqualTo(now);
        assertThat(batch.getFailureReason()).isEqualTo("some error");
    }
}
