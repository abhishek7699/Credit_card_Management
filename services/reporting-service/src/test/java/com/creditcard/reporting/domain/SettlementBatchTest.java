package com.creditcard.reporting.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementBatchTest {

    @Test
    void allGettersAndSetters() {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchId("batch-001");
        batch.setTriggeredBy("SCHEDULER");
        batch.setStatus("COMPLETED");
        batch.setTotalTransactionsProcessed(1250);
        batch.setTotalGrossAmount(new BigDecimal("312500.00"));
        batch.setTotalInterchangeFees(new BigDecimal("6250.00"));
        batch.setTotalNetAmount(new BigDecimal("306250.00"));
        Instant started = Instant.parse("2026-06-13T23:59:00Z");
        Instant completed = Instant.parse("2026-06-13T23:59:47Z");
        batch.setStartedAt(started);
        batch.setCompletedAt(completed);
        batch.setFailureReason(null);

        assertThat(batch.getBatchId()).isEqualTo("batch-001");
        assertThat(batch.getTriggeredBy()).isEqualTo("SCHEDULER");
        assertThat(batch.getStatus()).isEqualTo("COMPLETED");
        assertThat(batch.getTotalTransactionsProcessed()).isEqualTo(1250);
        assertThat(batch.getTotalGrossAmount()).isEqualByComparingTo("312500.00");
        assertThat(batch.getTotalInterchangeFees()).isEqualByComparingTo("6250.00");
        assertThat(batch.getTotalNetAmount()).isEqualByComparingTo("306250.00");
        assertThat(batch.getStartedAt()).isEqualTo(started);
        assertThat(batch.getCompletedAt()).isEqualTo(completed);
        assertThat(batch.getFailureReason()).isNull();
    }

    @Test
    void failedBatch_storesFailureReason() {
        SettlementBatch batch = new SettlementBatch();
        batch.setStatus("FAILED");
        batch.setFailureReason("Database timeout");

        assertThat(batch.getStatus()).isEqualTo("FAILED");
        assertThat(batch.getFailureReason()).isEqualTo("Database timeout");
    }

    @Test
    void hasEntityAnnotation() {
        assertThat(SettlementBatch.class).hasAnnotation(Entity.class);
    }

    @Test
    void hasCorrectTableName() {
        Table table = SettlementBatch.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("settlement_batches");
    }
}
