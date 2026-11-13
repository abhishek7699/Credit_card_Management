package com.creditcard.transaction.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound event on {@code transaction.authorized}, consumed by settlement-service (013 option Y).
 * transaction-service publishes it only when its conditional PENDING→AUTHORIZED update wins, so a
 * timed-out transaction never reaches settlement. The shape matches settlement's own
 * TransactionAuthorizedEvent (specs/005-settlement-service/contracts/events.md).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionAuthorizedEvent {

    private String eventId;
    private String eventType;
    private String sourceService;
    private String traceId;
    private Instant occurredAt;
    private Payload payload;

    public TransactionAuthorizedEvent() {}

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
        private String transactionId;
        private String merchantId;
        private String cardToken;
        private String maskedCardNumber;
        private BigDecimal amount;
        private String currencyCode;
        private Instant authorizedAt;

        public Payload() {}

        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

        public String getMerchantId() { return merchantId; }
        public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

        public String getCardToken() { return cardToken; }
        public void setCardToken(String cardToken) { this.cardToken = cardToken; }

        public String getMaskedCardNumber() { return maskedCardNumber; }
        public void setMaskedCardNumber(String maskedCardNumber) { this.maskedCardNumber = maskedCardNumber; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

        public Instant getAuthorizedAt() { return authorizedAt; }
        public void setAuthorizedAt(Instant authorizedAt) { this.authorizedAt = authorizedAt; }
    }
}
