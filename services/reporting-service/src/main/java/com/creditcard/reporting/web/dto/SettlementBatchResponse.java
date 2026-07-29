package com.creditcard.reporting.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;

public class SettlementBatchResponse {

    private String batchId;
    private String status;
    private String triggeredBy;
    private int totalTransactionsProcessed;
    private BigDecimal totalGrossAmount;
    private BigDecimal totalInterchangeFees;
    private BigDecimal totalNetAmount;
    private Instant startedAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Instant completedAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String failureReason;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public int getTotalTransactionsProcessed() { return totalTransactionsProcessed; }
    public void setTotalTransactionsProcessed(int totalTransactionsProcessed) {
        this.totalTransactionsProcessed = totalTransactionsProcessed;
    }

    public BigDecimal getTotalGrossAmount() { return totalGrossAmount; }
    public void setTotalGrossAmount(BigDecimal totalGrossAmount) { this.totalGrossAmount = totalGrossAmount; }

    public BigDecimal getTotalInterchangeFees() { return totalInterchangeFees; }
    public void setTotalInterchangeFees(BigDecimal totalInterchangeFees) { this.totalInterchangeFees = totalInterchangeFees; }

    public BigDecimal getTotalNetAmount() { return totalNetAmount; }
    public void setTotalNetAmount(BigDecimal totalNetAmount) { this.totalNetAmount = totalNetAmount; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
