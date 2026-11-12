package com.creditcard.authorization.kafka.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbound event on {@code transaction.timed_out} (013), published by transaction-service's PENDING sweep.
 * Its envelope matches the other events on the topic; authorization needs only the transactionId and the
 * card token to release the credit held for it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TransactionTimedOutEvent {

    private String eventId;
    private String eventType;
    private String sourceService;
    private String traceId;
    private Instant occurredAt;
    private Payload payload;

    public TransactionTimedOutEvent() {}

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

        public Payload() {}

        public UUID getTransactionId() { return transactionId; }
        public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

        public String getCardToken() { return cardToken; }
        public void setCardToken(String cardToken) { this.cardToken = cardToken; }
    }
}
