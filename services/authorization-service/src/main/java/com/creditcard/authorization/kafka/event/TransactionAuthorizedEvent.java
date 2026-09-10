package com.creditcard.authorization.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Outbound event on {@code transaction.authorized}, consumed by settlement-service. Published
 * only for the AUTHORIZED outcome. Shape mirrors settlement-service's own
 * {@code TransactionAuthorizedEvent} (see specs/005-settlement-service/contracts/events.md).
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

    /** Builds the event from the inbound {@code transaction.initiated} payload. */
    public static TransactionAuthorizedEvent from(TransactionInitiatedEvent.Payload src, Instant authorizedAt) {
        TransactionAuthorizedEvent e = new TransactionAuthorizedEvent();
        e.eventId = UUID.randomUUID().toString();
        e.eventType = "TransactionAuthorized";
        e.sourceService = "authorization-service";
        e.occurredAt = Instant.now();
        Payload p = new Payload();
        p.setTransactionId(src.getTransactionId());
        p.setMerchantId(src.getMerchantId());
        p.setCardToken(src.getCardToken());
        p.setMaskedCardNumber(src.getMaskedCardNumber());
        p.setAmount(src.getAmount());
        p.setCurrencyCode(src.getCurrencyCode());
        p.setAuthorizedAt(authorizedAt);
        e.payload = p;
        return e;
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
        private String cardToken;
        private String maskedCardNumber;
        private BigDecimal amount;
        private String currencyCode;
        private Instant authorizedAt;

        public UUID getTransactionId() { return transactionId; }
        public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

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
