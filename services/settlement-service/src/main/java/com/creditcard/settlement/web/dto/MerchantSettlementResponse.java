package com.creditcard.settlement.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class MerchantSettlementResponse {

    private String merchantId;
    private String batchId;
    private int transactionCount;
    private BigDecimal grossAmount;
    private BigDecimal totalInterchangeFee;
    private BigDecimal netSettlementAmount;
    private String currencyCode;
    private LocalDate settlementDate;

    public MerchantSettlementResponse() {}

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public int getTransactionCount() { return transactionCount; }
    public void setTransactionCount(int transactionCount) { this.transactionCount = transactionCount; }

    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }

    public BigDecimal getTotalInterchangeFee() { return totalInterchangeFee; }
    public void setTotalInterchangeFee(BigDecimal totalInterchangeFee) {
        this.totalInterchangeFee = totalInterchangeFee;
    }

    public BigDecimal getNetSettlementAmount() { return netSettlementAmount; }
    public void setNetSettlementAmount(BigDecimal netSettlementAmount) {
        this.netSettlementAmount = netSettlementAmount;
    }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }
}
