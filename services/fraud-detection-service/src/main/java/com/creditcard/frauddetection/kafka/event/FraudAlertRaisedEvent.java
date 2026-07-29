package com.creditcard.frauddetection.kafka.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class FraudAlertRaisedEvent {

    private UUID eventId;
    private String eventType;
    private UUID alertId;
    private UUID transactionId;
    private String cardToken;
    private List<String> triggeredRuleTypes;
    private Instant detectedAt;
    private String sourceService;
    private String traceId;

    public static FraudAlertRaisedEvent of(UUID alertId, UUID transactionId, String cardToken,
                                            List<String> triggeredRuleTypes, Instant detectedAt,
                                            String traceId) {
        FraudAlertRaisedEvent event = new FraudAlertRaisedEvent();
        event.eventId = UUID.randomUUID();
        event.eventType = "FraudAlertRaised";
        event.alertId = alertId;
        event.transactionId = transactionId;
        event.cardToken = cardToken;
        event.triggeredRuleTypes = triggeredRuleTypes;
        event.detectedAt = detectedAt;
        event.sourceService = "fraud-detection-service";
        event.traceId = traceId;
        return event;
    }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public UUID getAlertId() { return alertId; }
    public void setAlertId(UUID alertId) { this.alertId = alertId; }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public String getCardToken() { return cardToken; }
    public void setCardToken(String cardToken) { this.cardToken = cardToken; }

    public List<String> getTriggeredRuleTypes() { return triggeredRuleTypes; }
    public void setTriggeredRuleTypes(List<String> triggeredRuleTypes) { this.triggeredRuleTypes = triggeredRuleTypes; }

    public Instant getDetectedAt() { return detectedAt; }
    public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }

    public String getSourceService() { return sourceService; }
    public void setSourceService(String sourceService) { this.sourceService = sourceService; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
