package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;
import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit coverage for the outbox classes (013). Integration behavior is in OutboxRelayIntegrationTest. */
@ExtendWith(MockitoExtension.class)
class OutboxUnitTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, StatusUpdateEvent> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private StatusUpdateEvent sampleEvent(UUID eventId) {
        StatusUpdateEvent.Payload payload = new StatusUpdateEvent.Payload();
        payload.setTransactionId(UUID.randomUUID().toString());
        payload.setOutcome("AUTHORIZED");
        StatusUpdateEvent event = new StatusUpdateEvent();
        event.setEventId(eventId.toString());
        event.setEventType("TransactionAuthorized");
        event.setSourceService("transaction-service");
        event.setOccurredAt(Instant.parse("2026-11-11T10:00:00Z"));
        event.setPayload(payload);
        return event;
    }

    // ── OutboxService ─────────────────────────────────────────────────────────

    @Test
    void appendStoresTheEnvelopeAsJsonWithStableIdentity() {
        OutboxService service = new OutboxService(outboxRepository, objectMapper);
        UUID eventId = UUID.randomUUID();
        StatusUpdateEvent event = sampleEvent(eventId);

        service.append("transaction.status.updated", eventId, "tx-key", event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent row = captor.getValue();
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getTopic()).isEqualTo("transaction.status.updated");
        assertThat(row.getMessageKey()).isEqualTo("tx-key");
        assertThat(row.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(row.getPayload()).contains("\"outcome\":\"AUTHORIZED\"");
        assertThat(row.getSentAt()).isNull();
    }

    @Test
    void appendWrapsSerializationFailure() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonGenerationException("boom", (com.fasterxml.jackson.core.JsonGenerator) null));
        OutboxService service = new OutboxService(outboxRepository, failing);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> service.append("t", eventId, "k", sampleEvent(eventId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(eventId.toString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void outboxEventExposesItsFields() {
        UUID id = UUID.randomUUID();
        OutboxEvent row = new OutboxEvent(id, "topic", "key", "type", "{}");

        row.setSentAt(Instant.EPOCH);

        assertThat(row.getEventId()).isEqualTo(id);
        assertThat(row.getTopic()).isEqualTo("topic");
        assertThat(row.getMessageKey()).isEqualTo("key");
        assertThat(row.getEventType()).isEqualTo("type");
        assertThat(row.getPayload()).isEqualTo("{}");
        assertThat(row.getSentAt()).isEqualTo(Instant.EPOCH);
        assertThat(row.getCreatedAt()).isNull();
    }

    @Test
    void publisherReportsInterruptionAndRestoresTheFlag() throws Exception {
        UUID eventId = UUID.randomUUID();
        String json = objectMapper.writeValueAsString(sampleEvent(eventId));
        when(kafkaTemplate.send(anyString(), anyString(), any(StatusUpdateEvent.class)))
                .thenReturn(new CompletableFuture<>());
        OutboxPublisher publisher = new OutboxPublisher(outboxRepository, objectMapper, kafkaTemplate);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publish(rowFor(eventId, json)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(outboxRepository, never()).markSent(any(), any());
    }

    // ── OutboxPublisher ───────────────────────────────────────────────────────

    private OutboxEvent rowFor(UUID eventId, String json) {
        return new OutboxEvent(eventId, "transaction.status.updated", "tx-key", "TransactionAuthorized", json);
    }

    @Test
    void publisherSendsTheRowThenMarksItSent() throws Exception {
        UUID eventId = UUID.randomUUID();
        String json = objectMapper.writeValueAsString(sampleEvent(eventId));
        when(kafkaTemplate.send(anyString(), anyString(), any(StatusUpdateEvent.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        OutboxPublisher publisher = new OutboxPublisher(outboxRepository, objectMapper, kafkaTemplate);

        publisher.publish(rowFor(eventId, json));

        verify(kafkaTemplate).send(eq("transaction.status.updated"), eq("tx-key"), any(StatusUpdateEvent.class));
        verify(outboxRepository).markSent(eq(eventId), any(Instant.class));
    }

    @Test
    void publisherDoesNotMarkSentWhenTheSendFails() throws Exception {
        UUID eventId = UUID.randomUUID();
        String json = objectMapper.writeValueAsString(sampleEvent(eventId));
        CompletableFuture<SendResult<String, StatusUpdateEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), any(StatusUpdateEvent.class))).thenReturn(failed);
        OutboxPublisher publisher = new OutboxPublisher(outboxRepository, objectMapper, kafkaTemplate);

        assertThatThrownBy(() -> publisher.publish(rowFor(eventId, json)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(eventId.toString());
        verify(outboxRepository, never()).markSent(any(), any());
    }

    @Test
    void publisherRejectsRowWithInvalidJson() {
        UUID eventId = UUID.randomUUID();
        OutboxPublisher publisher = new OutboxPublisher(outboxRepository, objectMapper, kafkaTemplate);

        assertThatThrownBy(() -> publisher.publish(rowFor(eventId, "not json")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid JSON");
        verifyNoInteractions(kafkaTemplate);
    }

    // ── OutboxRelay ───────────────────────────────────────────────────────────

    @Test
    void relayPublishesEveryUnsentRowOldestFirst() {
        OutboxPublisher publisher = mock(OutboxPublisher.class);
        OutboxRelay relay = new OutboxRelay(outboxRepository, publisher, true);
        OutboxEvent first = rowFor(UUID.randomUUID(), "{}");
        OutboxEvent second = rowFor(UUID.randomUUID(), "{}");
        when(outboxRepository.findTop100BySentAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));

        int sent = relay.relayBatch();

        assertThat(sent).isEqualTo(2);
        verify(publisher).publish(first);
        verify(publisher).publish(second);
    }

    @Test
    void relayStopsAtTheFirstFailureToKeepOrder() {
        OutboxPublisher publisher = mock(OutboxPublisher.class);
        OutboxRelay relay = new OutboxRelay(outboxRepository, publisher, true);
        OutboxEvent first = rowFor(UUID.randomUUID(), "{}");
        OutboxEvent second = rowFor(UUID.randomUUID(), "{}");
        when(outboxRepository.findTop100BySentAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(first);

        int sent = relay.relayBatch();

        assertThat(sent).isZero();
        verify(publisher, never()).publish(second);
    }

    @Test
    void scheduledPassDoesNothingWhenDisabled() {
        OutboxPublisher publisher = mock(OutboxPublisher.class);
        OutboxRelay relay = new OutboxRelay(outboxRepository, publisher, false);

        relay.relayPending();

        verifyNoInteractions(outboxRepository, publisher);
    }

    @Test
    void scheduledPassRunsTheBatchWhenEnabled() {
        OutboxPublisher publisher = mock(OutboxPublisher.class);
        OutboxRelay relay = new OutboxRelay(outboxRepository, publisher, true);
        when(outboxRepository.findTop100BySentAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        relay.relayPending();

        verify(outboxRepository).findTop100BySentAtIsNullOrderByCreatedAtAsc();
    }
}
