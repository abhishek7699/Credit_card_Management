package com.creditcard.reporting.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantSettlementTest {

    @Test
    void allGettersAndSetters() {
        MerchantSettlement ms = new MerchantSettlement();
        ms.setId("ms-001");
        ms.setBatchId("batch-001");
        ms.setMerchantId("merchant-001");
        ms.setTransactionCount(5);
        ms.setGrossAmount(new BigDecimal("500.00"));
        ms.setTotalInterchangeFee(new BigDecimal("10.00"));
        ms.setNetSettlementAmount(new BigDecimal("490.00"));
        ms.setCurrencyCode("USD");
        LocalDate date = LocalDate.of(2026, 6, 13);
        ms.setSettlementDate(date);
        Instant now = Instant.now();
        ms.setCreatedAt(now);

        assertThat(ms.getId()).isEqualTo("ms-001");
        assertThat(ms.getBatchId()).isEqualTo("batch-001");
        assertThat(ms.getMerchantId()).isEqualTo("merchant-001");
        assertThat(ms.getTransactionCount()).isEqualTo(5);
        assertThat(ms.getGrossAmount()).isEqualByComparingTo("500.00");
        assertThat(ms.getTotalInterchangeFee()).isEqualByComparingTo("10.00");
        assertThat(ms.getNetSettlementAmount()).isEqualByComparingTo("490.00");
        assertThat(ms.getCurrencyCode()).isEqualTo("USD");
        assertThat(ms.getSettlementDate()).isEqualTo(date);
        assertThat(ms.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void hasEntityAnnotation() {
        assertThat(MerchantSettlement.class).hasAnnotation(Entity.class);
    }

    @Test
    void hasCorrectTableName() {
        Table table = MerchantSettlement.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("merchant_settlements");
    }
}
