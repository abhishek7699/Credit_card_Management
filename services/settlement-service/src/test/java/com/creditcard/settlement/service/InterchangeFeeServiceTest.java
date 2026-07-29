package com.creditcard.settlement.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class InterchangeFeeServiceTest {

    private InterchangeFeeService serviceWith(String rate) {
        return new InterchangeFeeService(new BigDecimal(rate));
    }

    @Test
    void calculateFee_standardAmount_twoPercentHalfUp() {
        assertThat(serviceWith("0.02").calculateFee(new BigDecimal("100.00")))
                .isEqualByComparingTo("2.00");
    }

    @Test
    void calculateFee_roundsHalfUp() {
        // 150.75 * 0.02 = 3.015 → rounds to 3.02
        assertThat(serviceWith("0.02").calculateFee(new BigDecimal("150.75")))
                .isEqualByComparingTo("3.02");
    }

    @Test
    void calculateFee_verySmallAmount_roundsToZero() {
        // 0.01 * 0.02 = 0.0002 → rounds to 0.00
        assertThat(serviceWith("0.02").calculateFee(new BigDecimal("0.01")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void calculateFee_largeAmount_correct() {
        assertThat(serviceWith("0.02").calculateFee(new BigDecimal("10000.00")))
                .isEqualByComparingTo("200.00");
    }

    @Test
    void calculateFee_zeroRate_returnsZero() {
        assertThat(serviceWith("0.00").calculateFee(new BigDecimal("500.00")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void getRate_returnsConfiguredRate() {
        InterchangeFeeService svc = serviceWith("0.025");
        assertThat(svc.getInterchangeFeeRate()).isEqualByComparingTo("0.025");
    }
}
