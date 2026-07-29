package com.creditcard.settlement.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementTransactionTest {

    @Test
    void defaultStatus_isAuthorized() {
        SettlementTransaction tx = new SettlementTransaction();
        assertThat(tx.getStatus()).isEqualTo("AUTHORIZED");
    }

    @Test
    void defaultCurrencyCode_isUsd() {
        SettlementTransaction tx = new SettlementTransaction();
        assertThat(tx.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void prePersist_setsCreatedAt() {
        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId(UUID.randomUUID());
        assertThat(tx.getCreatedAt()).isNull();
    }

    @Test
    void gettersAndSetters() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Instant auth = now.minusSeconds(3600);

        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId(id);
        tx.setMerchantId("merchant-001");
        tx.setCardToken("vault:v1:tok");
        tx.setMaskedCardNumber("****-****-****-1234");
        tx.setAmount(new BigDecimal("100.00"));
        tx.setCurrencyCode("USD");
        tx.setStatus("SETTLED");
        tx.setBatchId("batch-001");
        tx.setSettlementFee(new BigDecimal("2.00"));
        tx.setNetAmount(new BigDecimal("98.00"));
        tx.setAuthorizedAt(auth);
        tx.setSettledAt(now);
        tx.setCreatedAt(now);

        assertThat(tx.getTransactionId()).isEqualTo(id);
        assertThat(tx.getMerchantId()).isEqualTo("merchant-001");
        assertThat(tx.getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(tx.getMaskedCardNumber()).isEqualTo("****-****-****-1234");
        assertThat(tx.getAmount()).isEqualByComparingTo("100.00");
        assertThat(tx.getCurrencyCode()).isEqualTo("USD");
        assertThat(tx.getStatus()).isEqualTo("SETTLED");
        assertThat(tx.getBatchId()).isEqualTo("batch-001");
        assertThat(tx.getSettlementFee()).isEqualByComparingTo("2.00");
        assertThat(tx.getNetAmount()).isEqualByComparingTo("98.00");
        assertThat(tx.getAuthorizedAt()).isEqualTo(auth);
        assertThat(tx.getSettledAt()).isEqualTo(now);
        assertThat(tx.getCreatedAt()).isEqualTo(now);
    }
}
