package com.creditcard.frauddetection.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionRecordTest {

    @Test
    void shouldSetAndGetAllFields() {
        TransactionRecord record = new TransactionRecord();
        UUID id = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        Instant now = Instant.now();

        record.setId(id);
        record.setTransactionId(txId);
        record.setCardToken("vault:v1:tok");
        record.setAmount(new BigDecimal("150.00"));
        record.setCurrencyCode("USD");
        record.setOccurredAt(now);

        assertThat(record.getId()).isEqualTo(id);
        assertThat(record.getTransactionId()).isEqualTo(txId);
        assertThat(record.getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(record.getAmount()).isEqualByComparingTo("150.00");
        assertThat(record.getCurrencyCode()).isEqualTo("USD");
        assertThat(record.getOccurredAt()).isEqualTo(now);
    }
}
