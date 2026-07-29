package com.creditcard.reporting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Immutable
@Table(name = "settlement_batches")
public class SettlementBatch {

    @Id
    @Column(name = "batch_id", updatable = false, nullable = false)
    private String batchId;

    @Column(name = "triggered_by", nullable = false, length = 20)
    private String triggeredBy;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "total_transactions_processed", nullable = false)
    private int totalTransactionsProcessed;

    @Column(name = "total_gross_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalGrossAmount;

    @Column(name = "total_interchange_fees", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalInterchangeFees;

    @Column(name = "total_net_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalNetAmount;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public String getTriggeredBy() { return triggeredBy; }
    public void setTriggeredBy(String triggeredBy) { this.triggeredBy = triggeredBy; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

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
