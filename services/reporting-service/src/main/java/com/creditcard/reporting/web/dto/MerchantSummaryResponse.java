package com.creditcard.reporting.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class MerchantSummaryResponse {

    private String merchantId;
    private long transactionCount;
    private BigDecimal grossAmount;
    private BigDecimal totalInterchangeFee;
    private BigDecimal netSettlementAmount;
    private String currencyCode;
    private long batchCount;
    private LocalDate from;
    private LocalDate to;

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public long getTransactionCount() { return transactionCount; }
    public void setTransactionCount(long transactionCount) { this.transactionCount = transactionCount; }

    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }

    public BigDecimal getTotalInterchangeFee() { return totalInterchangeFee; }
    public void setTotalInterchangeFee(BigDecimal totalInterchangeFee) { this.totalInterchangeFee = totalInterchangeFee; }

    public BigDecimal getNetSettlementAmount() { return netSettlementAmount; }
    public void setNetSettlementAmount(BigDecimal netSettlementAmount) { this.netSettlementAmount = netSettlementAmount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public long getBatchCount() { return batchCount; }
    public void setBatchCount(long batchCount) { this.batchCount = batchCount; }

    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate from) { this.from = from; }

    public LocalDate getTo() { return to; }
    public void setTo(LocalDate to) { this.to = to; }
}
