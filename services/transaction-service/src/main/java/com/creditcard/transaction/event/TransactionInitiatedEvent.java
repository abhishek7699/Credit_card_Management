package com.creditcard.transaction.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class TransactionInitiatedEvent {

    private String eventId;
    private String eventType = "TransactionInitiated";
    private String sourceService = "transaction-service";
    private String traceId;
    private Instant occurredAt;
    private Payload payload;

    public TransactionInitiatedEvent() {}

    public TransactionInitiatedEvent(String traceId, Payload payload) {
        this.eventId = UUID.randomUUID().toString();
        this.traceId = traceId;
        this.occurredAt = Instant.now();
        this.payload = payload;
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

    public static class Payload {
        private String transactionId;
        private String cardToken;
        private String maskedCardNumber;
        private String cardholderName;
        private BigDecimal amount;
        private String currencyCode;

        public Payload() {}

        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

        public String getCardToken() { return cardToken; }
        public void setCardToken(String cardToken) { this.cardToken = cardToken; }

        public String getMaskedCardNumber() { return maskedCardNumber; }
        public void setMaskedCardNumber(String maskedCardNumber) { this.maskedCardNumber = maskedCardNumber; }

        public String getCardholderName() { return cardholderName; }
        public void setCardholderName(String cardholderName) { this.cardholderName = cardholderName; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    }
}
