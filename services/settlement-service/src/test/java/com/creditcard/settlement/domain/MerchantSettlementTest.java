package com.creditcard.settlement.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantSettlementTest {

    @Test
    void defaultCurrencyCode_isUsd() {
        MerchantSettlement ms = new MerchantSettlement();
        assertThat(ms.getCurrencyCode()).isEqualTo("USD");
    }

    @Test
    void onCreate_setsCreatedAt() throws Exception {
        MerchantSettlement ms = new MerchantSettlement();
        assertThat(ms.getCreatedAt()).isNull();
        Method onCreate = MerchantSettlement.class.getDeclaredMethod("onCreate");
        onCreate.setAccessible(true);
        onCreate.invoke(ms);
        assertThat(ms.getCreatedAt()).isNotNull();
    }

    @Test
    void gettersAndSetters() {
        UUID id = UUID.randomUUID();
        LocalDate today = LocalDate.now();
        Instant now = Instant.now();

        MerchantSettlement ms = new MerchantSettlement();
        ms.setId(id);
        ms.setBatchId("batch-001");
        ms.setMerchantId("merchant-001");
        ms.setTransactionCount(5);
        ms.setGrossAmount(new BigDecimal("500.00"));
        ms.setTotalInterchangeFee(new BigDecimal("10.00"));
        ms.setNetSettlementAmount(new BigDecimal("490.00"));
        ms.setCurrencyCode("USD");
        ms.setSettlementDate(today);
        ms.setCreatedAt(now);

        assertThat(ms.getId()).isEqualTo(id);
        assertThat(ms.getBatchId()).isEqualTo("batch-001");
        assertThat(ms.getMerchantId()).isEqualTo("merchant-001");
        assertThat(ms.getTransactionCount()).isEqualTo(5);
        assertThat(ms.getGrossAmount()).isEqualByComparingTo("500.00");
        assertThat(ms.getTotalInterchangeFee()).isEqualByComparingTo("10.00");
        assertThat(ms.getNetSettlementAmount()).isEqualByComparingTo("490.00");
        assertThat(ms.getCurrencyCode()).isEqualTo("USD");
        assertThat(ms.getSettlementDate()).isEqualTo(today);
        assertThat(ms.getCreatedAt()).isEqualTo(now);
    }
}
