package com.creditcard.authorization.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Inbound event on {@code transaction.initiated}. Matches the envelope + {@code payload}
 * contract published by transaction-service (see specs/002-authorization-service/contracts/kafka-events.md).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionInitiatedEvent {

    private String eventId;
    private String eventType;
    private String sourceService;
    private String traceId;
    private Instant occurredAt;
    private Payload payload;

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
        private String cardToken;
        private String maskedCardNumber;
        private String cardholderName;
        private BigDecimal amount;
        private String currencyCode;
        private String merchantId;

        public UUID getTransactionId() { return transactionId; }
        public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

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

        public String getMerchantId() { return merchantId; }
        public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    }
}
