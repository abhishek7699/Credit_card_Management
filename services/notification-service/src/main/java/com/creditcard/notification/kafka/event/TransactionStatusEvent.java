package com.creditcard.notification.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * Inbound event on {@code transaction.status.updated}. Matches the envelope + {@code payload}
 * contract actually published by authorization-service's {@code AuthorizationResultEvent}
 * (see specs/002-authorization-service/contracts/kafka-events.md).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionStatusEvent {

    private String eventId;
    private String eventType;
    private String sourceService;
    private String traceId;
    private Instant occurredAt;
    private Payload payload;

    public TransactionStatusEvent() {}

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
        private String cardToken;
        private String outcome;
        private String reasonCode;

        public Payload() {}

        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

        public String getCardToken() { return cardToken; }
        public void setCardToken(String cardToken) { this.cardToken = cardToken; }

        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }

        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    }
}
