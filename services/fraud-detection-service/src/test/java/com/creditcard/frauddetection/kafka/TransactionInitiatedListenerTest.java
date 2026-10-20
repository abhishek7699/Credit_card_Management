package com.creditcard.frauddetection.kafka;

import com.creditcard.frauddetection.kafka.event.FraudAlertRaisedEvent;
import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;
import com.creditcard.frauddetection.service.FraudDetectionService;
import com.creditcard.frauddetection.service.IdempotencyService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionInitiatedListenerTest {

    @Mock
    private FraudDetectionService fraudDetectionService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private KafkaTemplate<String, FraudAlertRaisedEvent> kafkaTemplate;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private TransactionInitiatedListener listener;

    @Test
    void shouldPublishAlertAndAckWhenFraudDetected() {
        TransactionInitiatedEvent event = buildEvent("evt-001");
        String idempotencyKey = "fraud:TransactionInitiated:evt-001";
        UUID transactionId = event.getPayload().getTransactionId();
        FraudAlertRaisedEvent alertEvent = buildAlertEvent(transactionId);

        when(idempotencyService.buildKey("TransactionInitiated", "evt-001")).thenReturn(idempotencyKey);
        when(idempotencyService.isDuplicate(idempotencyKey)).thenReturn(false);
        when(fraudDetectionService.evaluate(event)).thenReturn(Optional.of(alertEvent));

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 0L, transactionId.toString(), event);

        listener.onMessage(record, acknowledgment);

        ArgumentCaptor<FraudAlertRaisedEvent> captor = ArgumentCaptor.forClass(FraudAlertRaisedEvent.class);
        verify(kafkaTemplate).send(eq("fraud.alert.raised"), eq(transactionId.toString()), captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("FraudAlertRaised");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldNotPublishAndAckWhenCleanTransaction() {
        TransactionInitiatedEvent event = buildEvent("evt-clean");
        String idempotencyKey = "fraud:TransactionInitiated:evt-clean";
        String transactionId = event.getPayload().getTransactionId().toString();

        when(idempotencyService.buildKey("TransactionInitiated", "evt-clean")).thenReturn(idempotencyKey);
        when(idempotencyService.isDuplicate(idempotencyKey)).thenReturn(false);
        when(fraudDetectionService.evaluate(event)).thenReturn(Optional.empty());

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 1L, transactionId, event);

        listener.onMessage(record, acknowledgment);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldSkipDuplicateEventAndAck() {
        TransactionInitiatedEvent event = buildEvent("evt-dup");
        String idempotencyKey = "fraud:TransactionInitiated:evt-dup";
        String transactionId = event.getPayload().getTransactionId().toString();

        when(idempotencyService.buildKey("TransactionInitiated", "evt-dup")).thenReturn(idempotencyKey);
        when(idempotencyService.isDuplicate(idempotencyKey)).thenReturn(true);

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 2L, transactionId, event);

        listener.onMessage(record, acknowledgment);

        verify(fraudDetectionService, never()).evaluate(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldDiscardAndAckWhenEventIsNull() {
        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 3L, "some-key", null);

        listener.onMessage(record, acknowledgment);

        verify(fraudDetectionService, never()).evaluate(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldDiscardAndAckWhenPayloadIsNull() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId("evt-null-payload");
        event.setEventType("TransactionInitiated");
        // payload intentionally left unset — this is the exact shape produced when a
        // flat (pre-envelope) consumer binds an enveloped message: everything but the
        // top-level eventId/eventType comes back null.

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 4L, UUID.randomUUID().toString(), event);

        listener.onMessage(record, acknowledgment);

        verify(fraudDetectionService, never()).evaluate(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldDiscardAndAckWhenPayloadTransactionIdIsNull() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId("evt-null-tx");
        event.setEventType("TransactionInitiated");

        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setCardToken("vault:v1:tok");
        // transactionId intentionally left unset
        event.setPayload(payload);

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 5L, UUID.randomUUID().toString(), event);

        listener.onMessage(record, acknowledgment);

        verify(fraudDetectionService, never()).evaluate(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldDiscardAndAckWhenServiceThrows() {
        TransactionInitiatedEvent event = buildEvent("evt-err");
        String idempotencyKey = "fraud:TransactionInitiated:evt-err";
        String transactionId = event.getPayload().getTransactionId().toString();

        when(idempotencyService.buildKey("TransactionInitiated", "evt-err")).thenReturn(idempotencyKey);
        when(idempotencyService.isDuplicate(idempotencyKey)).thenReturn(false);
        when(fraudDetectionService.evaluate(event)).thenThrow(new RuntimeException("DB error"));

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 6L, transactionId, event);

        listener.onMessage(record, acknowledgment);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(acknowledgment).acknowledge();
    }

    private TransactionInitiatedEvent buildEvent(String eventId) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(eventId);
        event.setEventType("TransactionInitiated");
        event.setSourceService("transaction-service");
        event.setOccurredAt(Instant.now());

        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setCardToken("vault:v1:tok");
        payload.setAmount(new BigDecimal("100.00"));
        payload.setCurrencyCode("USD");
        event.setPayload(payload);
        return event;
    }

    private FraudAlertRaisedEvent buildAlertEvent(UUID transactionId) {
        return FraudAlertRaisedEvent.of(UUID.randomUUID(), transactionId, "vault:v1:tok",
                List.of("VELOCITY_EXCEEDED"), Instant.now(), null);
    }
}
