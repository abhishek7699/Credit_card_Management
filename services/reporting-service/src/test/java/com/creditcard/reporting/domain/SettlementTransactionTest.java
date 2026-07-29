package com.creditcard.reporting.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementTransactionTest {

    @Test
    void allGettersAndSetters() {
        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId("tx-001");
        tx.setCardToken("tok-abc");
        tx.setMaskedCardNumber("4111-****-****-1111");
        tx.setMerchantId("merchant-001");
        tx.setAmount(new BigDecimal("100.00"));
        tx.setCurrencyCode("USD");
        tx.setStatus("SETTLED");
        tx.setBatchId("batch-001");
        tx.setSettlementFee(new BigDecimal("2.00"));
        tx.setNetAmount(new BigDecimal("98.00"));
        Instant now = Instant.now();
        tx.setAuthorizedAt(now);
        tx.setSettledAt(now);
        tx.setCreatedAt(now);

        assertThat(tx.getTransactionId()).isEqualTo("tx-001");
        assertThat(tx.getCardToken()).isEqualTo("tok-abc");
        assertThat(tx.getMaskedCardNumber()).isEqualTo("4111-****-****-1111");
        assertThat(tx.getMerchantId()).isEqualTo("merchant-001");
        assertThat(tx.getAmount()).isEqualByComparingTo("100.00");
        assertThat(tx.getCurrencyCode()).isEqualTo("USD");
        assertThat(tx.getStatus()).isEqualTo("SETTLED");
        assertThat(tx.getBatchId()).isEqualTo("batch-001");
        assertThat(tx.getSettlementFee()).isEqualByComparingTo("2.00");
        assertThat(tx.getNetAmount()).isEqualByComparingTo("98.00");
        assertThat(tx.getAuthorizedAt()).isEqualTo(now);
        assertThat(tx.getSettledAt()).isEqualTo(now);
        assertThat(tx.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void hasEntityAnnotation() {
        assertThat(SettlementTransaction.class).hasAnnotation(Entity.class);
    }

    @Test
    void hasCorrectTableName() {
        Table table = SettlementTransaction.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("settlement_transactions");
    }
}
