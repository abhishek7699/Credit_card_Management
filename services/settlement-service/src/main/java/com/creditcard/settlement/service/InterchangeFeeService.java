package com.creditcard.settlement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class InterchangeFeeService {

    private final BigDecimal interchangeFeeRate;

    public InterchangeFeeService(BigDecimal interchangeFeeRate) {
        this.interchangeFeeRate = interchangeFeeRate;
    }

    public BigDecimal calculateFee(BigDecimal amount) {
        return amount.multiply(interchangeFeeRate).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getInterchangeFeeRate() {
        return interchangeFeeRate;
    }
}
