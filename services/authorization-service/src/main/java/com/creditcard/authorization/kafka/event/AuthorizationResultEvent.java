package com.creditcard.authorization.kafka.event;

import com.creditcard.authorization.domain.ReasonCode;

import java.time.Instant;
import java.util.UUID;

public class AuthorizationResultEvent {

    private String eventId;
    private String eventType;
    private UUID transactionId;
    private String cardToken;
    private String reasonCode;
    private Instant occurredAt;

    public AuthorizationResultEvent() {}

    private AuthorizationResultEvent(String eventType, UUID transactionId, String cardToken, ReasonCode reasonCode) {
        this.eventId = UUID.randomUUID().toString();
        this.eventType = eventType;
        this.transactionId = transactionId;
        this.cardToken = cardToken;
        this.reasonCode = reasonCode != null ? reasonCode.name() : null;
        this.occurredAt = Instant.now();
    }

    public static AuthorizationResultEvent authorized(UUID transactionId, String cardToken) {
        return new AuthorizationResultEvent("TransactionAuthorized", transactionId, cardToken, null);
    }

    public static AuthorizationResultEvent declined(UUID transactionId, String cardToken, ReasonCode reason) {
        return new AuthorizationResultEvent("TransactionDeclined", transactionId, cardToken, reason);
    }

    public static AuthorizationResultEvent failed(UUID transactionId, String cardToken, ReasonCode reason) {
        return new AuthorizationResultEvent("TransactionFailed", transactionId, cardToken, reason);
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public String getCardToken() { return cardToken; }
    public void setCardToken(String cardToken) { this.cardToken = cardToken; }

    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String reasonCode) { this.reasonCode = reasonCode; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
