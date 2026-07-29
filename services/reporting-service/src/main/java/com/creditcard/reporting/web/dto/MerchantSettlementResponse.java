package com.creditcard.reporting.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class MerchantSettlementResponse {

    private String id;
    private String batchId;
    private String merchantId;
    private int transactionCount;
    private BigDecimal grossAmount;
    private BigDecimal totalInterchangeFee;
    private BigDecimal netSettlementAmount;
    private String currencyCode;
    private LocalDate settlementDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public int getTransactionCount() { return transactionCount; }
    public void setTransactionCount(int transactionCount) { this.transactionCount = transactionCount; }

    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }

    public BigDecimal getTotalInterchangeFee() { return totalInterchangeFee; }
    public void setTotalInterchangeFee(BigDecimal totalInterchangeFee) { this.totalInterchangeFee = totalInterchangeFee; }

    public BigDecimal getNetSettlementAmount() { return netSettlementAmount; }
    public void setNetSettlementAmount(BigDecimal netSettlementAmount) { this.netSettlementAmount = netSettlementAmount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public LocalDate getSettlementDate() { return settlementDate; }
    public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }
}
