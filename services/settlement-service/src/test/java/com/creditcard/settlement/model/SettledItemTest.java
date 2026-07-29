package com.creditcard.settlement.model;

import com.creditcard.settlement.domain.SettlementTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SettledItemTest {

    @Test
    void constructor_setsAllFields() {
        SettlementTransaction tx = new SettlementTransaction();
        BigDecimal gross = new BigDecimal("100.00");
        BigDecimal fee = new BigDecimal("2.00");
        BigDecimal net = new BigDecimal("98.00");

        SettledItem item = new SettledItem(tx, gross, fee, net);

        assertThat(item.getTransaction()).isSameAs(tx);
        assertThat(item.getGrossAmount()).isEqualByComparingTo("100.00");
        assertThat(item.getInterchangeFee()).isEqualByComparingTo("2.00");
        assertThat(item.getNetAmount()).isEqualByComparingTo("98.00");
    }
}
