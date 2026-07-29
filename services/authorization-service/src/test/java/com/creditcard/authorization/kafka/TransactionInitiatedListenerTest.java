package com.creditcard.authorization.kafka;

import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.service.AuthorizationService;
import com.creditcard.authorization.service.IdempotencyService;
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
    private AuthorizationService authorizationService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private KafkaTemplate<String, AuthorizationResultEvent> kafkaTemplate;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private TransactionInitiatedListener listener;

    @Test
    void shouldProcessEventAndPublishResultAndAck() {
        TransactionInitiatedEvent event = buildEvent("evt-001", UUID.randomUUID(), "vault:v1:tok", "100.00");
        String idempotencyKey = "auth:TransactionInitiated:evt-001";
        AuthorizationResultEvent result = AuthorizationResultEvent.authorized(event.getTransactionId(), event.getCardToken());

        when(idempotencyService.buildKey("TransactionInitiated", "evt-001")).thenReturn(idempotencyKey);
        when(idempotencyService.isDuplicate(idempotencyKey)).thenReturn(false);
        when(authorizationService.authorize(event)).thenReturn(result);

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 0L, event.getTransactionId().toString(), event);

        listener.onMessage(record, acknowledgment);

        ArgumentCaptor<AuthorizationResultEvent> captor = ArgumentCaptor.forClass(AuthorizationResultEvent.class);
        verify(kafkaTemplate).send(eq("transaction.status.updated"), eq(event.getTransactionId().toString()), captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("TransactionAuthorized");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldSkipDuplicateEventAndAck() {
        TransactionInitiatedEvent event = buildEvent("evt-dup", UUID.randomUUID(), "vault:v1:tok", "50.00");
        String idempotencyKey = "auth:TransactionInitiated:evt-dup";

        when(idempotencyService.buildKey("TransactionInitiated", "evt-dup")).thenReturn(idempotencyKey);
        when(idempotencyService.isDuplicate(idempotencyKey)).thenReturn(true);

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 1L, event.getTransactionId().toString(), event);

        listener.onMessage(record, acknowledgment);

        verify(authorizationService, never()).authorize(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldPublishFailedEventAndAckWhenAuthorizationThrows() {
        TransactionInitiatedEvent event = buildEvent("evt-err", UUID.randomUUID(), "vault:v1:tok", "100.00");
        String idempotencyKey = "auth:TransactionInitiated:evt-err";

        when(idempotencyService.buildKey("TransactionInitiated", "evt-err")).thenReturn(idempotencyKey);
        when(idempotencyService.isDuplicate(idempotencyKey)).thenReturn(false);
        when(authorizationService.authorize(event)).thenThrow(new RuntimeException("DB error"));

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 2L, event.getTransactionId().toString(), event);

        listener.onMessage(record, acknowledgment);

        ArgumentCaptor<AuthorizationResultEvent> captor = ArgumentCaptor.forClass(AuthorizationResultEvent.class);
        verify(kafkaTemplate).send(eq("transaction.status.updated"), eq(event.getTransactionId().toString()), captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("TransactionFailed");
        assertThat(captor.getValue().getReasonCode()).isEqualTo(ReasonCode.INTERNAL_ERROR.name());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldPublishFailedAndAckWhenEventPayloadIsNull() {
        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 3L, "some-key", null);

        listener.onMessage(record, acknowledgment);

        ArgumentCaptor<AuthorizationResultEvent> captor = ArgumentCaptor.forClass(AuthorizationResultEvent.class);
        verify(kafkaTemplate).send(eq("transaction.status.updated"), anyString(), captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("TransactionFailed");
        assertThat(captor.getValue().getReasonCode()).isEqualTo(ReasonCode.INVALID_EVENT_PAYLOAD.name());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldUseKeyAsTransactionIdWhenPayloadNullAndKeyIsValidUuid() {
        UUID validUuid = UUID.randomUUID();
        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 4L, validUuid.toString(), null);

        listener.onMessage(record, acknowledgment);

        ArgumentCaptor<AuthorizationResultEvent> captor = ArgumentCaptor.forClass(AuthorizationResultEvent.class);
        verify(kafkaTemplate).send(eq("transaction.status.updated"), eq(validUuid.toString()), captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("TransactionFailed");
        assertThat(captor.getValue().getTransactionId()).isEqualTo(validUuid);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldPublishFailedAndAckWhenEventHasNullTransactionId() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        // transactionId intentionally left null
        event.setEventId("evt-null-tx");
        event.setEventType("TransactionInitiated");
        event.setCardToken("vault:v1:tok");

        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 5L, UUID.randomUUID().toString(), event);

        listener.onMessage(record, acknowledgment);

        ArgumentCaptor<AuthorizationResultEvent> captor = ArgumentCaptor.forClass(AuthorizationResultEvent.class);
        verify(kafkaTemplate).send(eq("transaction.status.updated"), anyString(), captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("TransactionFailed");
        assertThat(captor.getValue().getReasonCode()).isEqualTo(ReasonCode.INVALID_EVENT_PAYLOAD.name());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldGenerateRandomIdWhenPayloadNullAndKeyIsNull() {
        ConsumerRecord<String, TransactionInitiatedEvent> record = new ConsumerRecord<>(
                "transaction.initiated", 0, 6L, null, null);

        listener.onMessage(record, acknowledgment);

        ArgumentCaptor<AuthorizationResultEvent> captor = ArgumentCaptor.forClass(AuthorizationResultEvent.class);
        verify(kafkaTemplate).send(eq("transaction.status.updated"), anyString(), captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("TransactionFailed");
        assertThat(captor.getValue().getReasonCode()).isEqualTo(ReasonCode.INVALID_EVENT_PAYLOAD.name());
        verify(acknowledgment).acknowledge();
    }

    private TransactionInitiatedEvent buildEvent(String eventId, UUID txId, String token, String amount) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(eventId);
        event.setEventType("TransactionInitiated");
        event.setTransactionId(txId);
        event.setCardToken(token);
        event.setAmount(new BigDecimal(amount));
        event.setCurrencyCode("USD");
        event.setOccurredAt(Instant.now());
        return event;
    }
}
