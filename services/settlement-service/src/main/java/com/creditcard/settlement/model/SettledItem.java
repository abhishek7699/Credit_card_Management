package com.creditcard.settlement.model;

import com.creditcard.settlement.domain.SettlementTransaction;

import java.math.BigDecimal;

public class SettledItem {

    private final SettlementTransaction transaction;
    private final BigDecimal grossAmount;
    private final BigDecimal interchangeFee;
    private final BigDecimal netAmount;

    public SettledItem(SettlementTransaction transaction, BigDecimal grossAmount,
                       BigDecimal interchangeFee, BigDecimal netAmount) {
        this.transaction = transaction;
        this.grossAmount = grossAmount;
        this.interchangeFee = interchangeFee;
        this.netAmount = netAmount;
    }

    public SettlementTransaction getTransaction() { return transaction; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public BigDecimal getInterchangeFee() { return interchangeFee; }
    public BigDecimal getNetAmount() { return netAmount; }
}
