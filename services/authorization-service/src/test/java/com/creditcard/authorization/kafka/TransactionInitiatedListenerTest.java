package com.creditcard.authorization.kafka;

import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionAuthorizedEvent;
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

    private static final String RESULT_TOPIC = "transaction.status.updated";
    private static final String AUTHORIZED_TOPIC = "transaction.authorized";

    @Mock
    private AuthorizationService authorizationService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private TransactionInitiatedListener listener;

    @Test
    void shouldPublishResultAndAuthorizedEventAndAckOnAuthorizedOutcome() {
        UUID txId = UUID.randomUUID();
        TransactionInitiatedEvent event = buildEvent("evt-001", txId, "vault:v1:tok", "100.00", "merchant-9");
        AuthorizationResultEvent result = AuthorizationResultEvent.authorized(txId, "vault:v1:tok");

        when(idempotencyService.buildKey("TransactionInitiated", "evt-001")).thenReturn("auth:TransactionInitiated:evt-001");
        when(idempotencyService.isDuplicate("auth:TransactionInitiated:evt-001")).thenReturn(false);
        when(authorizationService.authorize(event)).thenReturn(result);

        listener.onMessage(record(event, txId.toString(), 0L), acknowledgment);

        ArgumentCaptor<Object> resultCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RESULT_TOPIC), eq(txId.toString()), resultCaptor.capture());
        assertThat(((AuthorizationResultEvent) resultCaptor.getValue()).getEventType()).isEqualTo("TransactionAuthorized");

        ArgumentCaptor<Object> authCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(AUTHORIZED_TOPIC), eq(txId.toString()), authCaptor.capture());
        TransactionAuthorizedEvent authorized = (TransactionAuthorizedEvent) authCaptor.getValue();
        assertThat(authorized.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(authorized.getSourceService()).isEqualTo("authorization-service");
        assertThat(authorized.getPayload().getTransactionId()).isEqualTo(txId);
        assertThat(authorized.getPayload().getMerchantId()).isEqualTo("merchant-9");
        assertThat(authorized.getPayload().getMaskedCardNumber()).isEqualTo("****-****-****-1111");
        assertThat(authorized.getPayload().getAmount()).isEqualByComparingTo("100.00");
        assertThat(authorized.getPayload().getAuthorizedAt()).isNotNull();
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldPublishOnlyResultOnDeclinedOutcome() {
        UUID txId = UUID.randomUUID();
        TransactionInitiatedEvent event = buildEvent("evt-dec", txId, "vault:v1:tok", "100.00", "merchant-1");
        AuthorizationResultEvent result = AuthorizationResultEvent.declined(txId, "vault:v1:tok", ReasonCode.INVALID_CARD);

        when(idempotencyService.buildKey("TransactionInitiated", "evt-dec")).thenReturn("k");
        when(idempotencyService.isDuplicate("k")).thenReturn(false);
        when(authorizationService.authorize(event)).thenReturn(result);

        listener.onMessage(record(event, txId.toString(), 1L), acknowledgment);

        verify(kafkaTemplate).send(eq(RESULT_TOPIC), eq(txId.toString()), any());
        verify(kafkaTemplate, never()).send(eq(AUTHORIZED_TOPIC), anyString(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldSkipDuplicateEventAndAck() {
        UUID txId = UUID.randomUUID();
        TransactionInitiatedEvent event = buildEvent("evt-dup", txId, "vault:v1:tok", "50.00", "m");

        when(idempotencyService.buildKey("TransactionInitiated", "evt-dup")).thenReturn("k");
        when(idempotencyService.isDuplicate("k")).thenReturn(true);

        listener.onMessage(record(event, txId.toString(), 2L), acknowledgment);

        verify(authorizationService, never()).authorize(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldPublishFailedEventAndAckWhenAuthorizationThrows() {
        UUID txId = UUID.randomUUID();
        TransactionInitiatedEvent event = buildEvent("evt-err", txId, "vault:v1:tok", "100.00", "m");

        when(idempotencyService.buildKey("TransactionInitiated", "evt-err")).thenReturn("k");
        when(idempotencyService.isDuplicate("k")).thenReturn(false);
        when(authorizationService.authorize(event)).thenThrow(new RuntimeException("DB error"));

        listener.onMessage(record(event, txId.toString(), 3L), acknowledgment);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RESULT_TOPIC), eq(txId.toString()), captor.capture());
        AuthorizationResultEvent failed = (AuthorizationResultEvent) captor.getValue();
        assertThat(failed.getEventType()).isEqualTo("TransactionFailed");
        assertThat(failed.getPayload().getReasonCode()).isEqualTo(ReasonCode.INTERNAL_ERROR.name());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldPublishFailedAndAckWhenEventIsNull() {
        listener.onMessage(record(null, "some-key", 4L), acknowledgment);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RESULT_TOPIC), anyString(), captor.capture());
        assertThat(((AuthorizationResultEvent) captor.getValue()).getPayload().getReasonCode())
                .isEqualTo(ReasonCode.INVALID_EVENT_PAYLOAD.name());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldPublishFailedAndAckWhenPayloadIsNull() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId("evt-np");
        event.setEventType("TransactionInitiated");
        // payload intentionally left null

        listener.onMessage(record(event, UUID.randomUUID().toString(), 5L), acknowledgment);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RESULT_TOPIC), anyString(), captor.capture());
        assertThat(((AuthorizationResultEvent) captor.getValue()).getEventType()).isEqualTo("TransactionFailed");
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldPublishFailedAndAckWhenPayloadTransactionIdIsNull() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId("evt-nt");
        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setCardToken("vault:v1:tok");
        event.setPayload(payload);

        listener.onMessage(record(event, UUID.randomUUID().toString(), 6L), acknowledgment);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RESULT_TOPIC), anyString(), captor.capture());
        assertThat(((AuthorizationResultEvent) captor.getValue()).getPayload().getReasonCode())
                .isEqualTo(ReasonCode.INVALID_EVENT_PAYLOAD.name());
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldUseKeyAsTransactionIdWhenEventNullAndKeyIsValidUuid() {
        UUID validUuid = UUID.randomUUID();

        listener.onMessage(record(null, validUuid.toString(), 7L), acknowledgment);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RESULT_TOPIC), eq(validUuid.toString()), captor.capture());
        assertThat(((AuthorizationResultEvent) captor.getValue()).getPayload().getTransactionId()).isEqualTo(validUuid);
        verify(acknowledgment).acknowledge();
    }

    @Test
    void shouldGenerateRandomIdWhenEventNullAndKeyIsNull() {
        listener.onMessage(record(null, null, 8L), acknowledgment);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(RESULT_TOPIC), anyString(), captor.capture());
        assertThat(((AuthorizationResultEvent) captor.getValue()).getEventType()).isEqualTo("TransactionFailed");
        verify(acknowledgment).acknowledge();
    }

    private ConsumerRecord<String, TransactionInitiatedEvent> record(TransactionInitiatedEvent event, String key, long offset) {
        return new ConsumerRecord<>("transaction.initiated", 0, offset, key, event);
    }

    private TransactionInitiatedEvent buildEvent(String eventId, UUID txId, String token, String amount, String merchantId) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(eventId);
        event.setEventType("TransactionInitiated");
        event.setOccurredAt(Instant.now());
        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(txId);
        payload.setCardToken(token);
        payload.setMaskedCardNumber("****-****-****-1111");
        payload.setCardholderName("Jane Smith");
        payload.setAmount(new BigDecimal(amount));
        payload.setCurrencyCode("USD");
        payload.setMerchantId(merchantId);
        event.setPayload(payload);
        return event;
    }
}
