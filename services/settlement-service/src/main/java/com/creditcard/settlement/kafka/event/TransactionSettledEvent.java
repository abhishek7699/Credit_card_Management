package com.creditcard.settlement.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionSettledEvent {

    private String eventId;
    private String eventType = "TransactionSettled";
    private String sourceService = "settlement-service";
    private String traceId;
    private Instant occurredAt;
    private Payload payload;

    public TransactionSettledEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getSourceService() { return sourceService; }
    public void setSourceService(String sourceService) { this.sourceService = sourceService; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public Payload getPayload() { return payload; }
    public void setPayload(Payload payload) { this.payload = payload; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Payload {
        private UUID transactionId;
        private String merchantId;
        private String batchId;
        private BigDecimal grossAmount;
        private BigDecimal interchangeFee;
        private BigDecimal netAmount;
        private String currencyCode;
        private LocalDate settlementDate;
        private Instant settledAt;

        public Payload() {}

        public UUID getTransactionId() { return transactionId; }
        public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

        public String getMerchantId() { return merchantId; }
        public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }

        public BigDecimal getGrossAmount() { return grossAmount; }
        public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }

        public BigDecimal getInterchangeFee() { return interchangeFee; }
        public void setInterchangeFee(BigDecimal interchangeFee) { this.interchangeFee = interchangeFee; }

        public BigDecimal getNetAmount() { return netAmount; }
        public void setNetAmount(BigDecimal netAmount) { this.netAmount = netAmount; }

        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

        public LocalDate getSettlementDate() { return settlementDate; }
        public void setSettlementDate(LocalDate settlementDate) { this.settlementDate = settlementDate; }

        public Instant getSettledAt() { return settledAt; }
        public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    }
}
