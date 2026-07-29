package com.creditcard.settlement.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionTest {

    @Test
    void alreadySettledException_containsTransactionId() {
        AlreadySettledException ex = new AlreadySettledException("tx-001");
        assertThat(ex.getMessage()).contains("tx-001");
        assertThat(ex.getTransactionId()).isEqualTo("tx-001");
    }

    @Test
    void merchantSettlementNotFoundException_containsMerchantAndBatch() {
        MerchantSettlementNotFoundException ex = new MerchantSettlementNotFoundException("M1", "batch-001");
        assertThat(ex.getMessage()).contains("M1").contains("batch-001");
        assertThat(ex.getMerchantId()).isEqualTo("M1");
        assertThat(ex.getBatchId()).isEqualTo("batch-001");
    }

    @Test
    void batchAlreadyRunningException_containsRunningBatchId() {
        BatchAlreadyRunningException ex = new BatchAlreadyRunningException("batch-running");
        assertThat(ex.getMessage()).isEqualTo("Settlement batch is already running");
        assertThat(ex.getRunningBatchId()).isEqualTo("batch-running");
    }
}
