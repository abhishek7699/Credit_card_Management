package com.creditcard.authorization.kafka.event;

import com.creditcard.authorization.domain.ReasonCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound event on {@code transaction.status.updated}, consumed by transaction-service
 * (and notification-service). Envelope + {@code payload} contract.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthorizationResultEvent {

    private String eventId;
    private String eventType;
    private String sourceService;
    private String traceId;
    private Instant occurredAt;
    private Payload payload;

    public AuthorizationResultEvent() {}

    private AuthorizationResultEvent(String eventType, UUID transactionId, String cardToken,
                                    String outcome, ReasonCode reasonCode) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.sourceService = "authorization-service";
        this.occurredAt = Instant.now();
        Payload p = new Payload();
        p.setTransactionId(transactionId);
        p.setCardToken(cardToken);
        p.setOutcome(outcome);
        p.setReasonCode(reasonCode != null ? reasonCode.name() : null);
        this.payload = p;
    }

    public static AuthorizationResultEvent authorized(UUID transactionId, String cardToken) {
        return new AuthorizationResultEvent("TransactionAuthorized", transactionId, cardToken, "AUTHORIZED", null);
    }

    public static AuthorizationResultEvent declined(UUID transactionId, String cardToken, ReasonCode reason) {
        return new AuthorizationResultEvent("TransactionDeclined", transactionId, cardToken, "DECLINED", reason);
    }

    public static AuthorizationResultEvent failed(UUID transactionId, String cardToken, ReasonCode reason) {
        return new AuthorizationResultEvent("TransactionFailed", transactionId, cardToken, "FAILED", reason);
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
        private String cardToken;
        private String outcome;
        private String reasonCode;

        public UUID getTransactionId() { return transactionId; }
        public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

        public String getCardToken() { return cardToken; }
        public void setCardToken(String cardToken) { this.cardToken = cardToken; }

        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }

        public String getReasonCode() { return reasonCode; }
        public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }
    }
}
