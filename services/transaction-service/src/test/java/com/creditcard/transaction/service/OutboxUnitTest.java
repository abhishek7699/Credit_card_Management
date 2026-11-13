package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;
import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.repository.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit coverage for the outbox classes (013). Integration behavior is in OutboxRelayIntegrationTest. */
@ExtendWith(MockitoExtension.class)
class OutboxUnitTest {

    private static final String TOPIC = "transaction.status.updated";

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private OutboxPublisher outboxPublisher;

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

    /** A row whose stored payload is {@code json}, keyed by a fixed transactionId. */
    private OutboxEvent rowFor(UUID eventId, String json) {
        return new OutboxEvent(eventId, TOPIC, "tx-key", "TransactionAuthorized", json);
    }

    /** A row keyed by {@code transactionId}, with a valid empty JSON payload. */
    private OutboxEvent rowForTx(UUID eventId, String transactionId) {
        return new OutboxEvent(eventId, TOPIC, transactionId, "TransactionAuthorized", "{}");
    }

    @BeforeEach
    void bindSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void unbindSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void runAfterCommit() {
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }
    }

    private CompletableFuture<SendResult<String, Object>> completed() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    private OutboxPublisher realPublisher() {
        return new OutboxPublisher(outboxRepository, objectMapper, kafkaTemplate);
    }

    private static void setAttempts(OutboxEvent row, int attempts) {
        try {
            var field = OutboxEvent.class.getDeclaredField("attempts");
            field.setAccessible(true);
            field.setInt(row, attempts);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── OutboxService ─────────────────────────────────────────────────────────

    @Test
    void appendStoresTheEnvelopeAsJsonWithStableIdentity() {
        OutboxService service = new OutboxService(outboxRepository, objectMapper, outboxPublisher);
        UUID eventId = UUID.randomUUID();

        service.append(TOPIC, eventId, "tx-key", "TransactionAuthorized", sampleEvent(eventId));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent row = captor.getValue();
        assertThat(row.getEventId()).isEqualTo(eventId);
        assertThat(row.getTopic()).isEqualTo(TOPIC);
        assertThat(row.getMessageKey()).isEqualTo("tx-key");
        assertThat(row.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(row.getPayload()).contains("\"outcome\":\"AUTHORIZED\"");
        assertThat(row.getSentAt()).isNull();
    }

    @Test
    void appendPublishesTheRowRightAfterCommit() {
        OutboxService service = new OutboxService(outboxRepository, objectMapper, outboxPublisher);
        UUID eventId = UUID.randomUUID();

        service.append(TOPIC, eventId, "tx-key", "TransactionAuthorized", sampleEvent(eventId));
        verifyNoInteractions(outboxPublisher);

        runAfterCommit();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxPublisher).publish(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo(eventId);
    }

    @Test
    void aFailureRightAfterCommitIsLoggedAndDoesNotReachTheCaller() {
        OutboxService service = new OutboxService(outboxRepository, objectMapper, outboxPublisher);
        UUID eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("broker down")).when(outboxPublisher).publish(any());

        service.append(TOPIC, eventId, "tx-key", "TransactionAuthorized", sampleEvent(eventId));
        runAfterCommit();

        verify(outboxPublisher).publish(any());
    }

    @Test
    void appendWrapsSerializationFailure() throws Exception {
        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new com.fasterxml.jackson.core.JsonGenerationException("boom", (com.fasterxml.jackson.core.JsonGenerator) null));
        OutboxService service = new OutboxService(outboxRepository, failing, outboxPublisher);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> service.append("t", eventId, "k", "type", sampleEvent(eventId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(eventId.toString());
        verify(outboxRepository, never()).save(any());
    }

    @Test
    void outboxEventExposesItsFieldsAndStartsWithNoAttempts() {
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
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getFailedAt()).isNull();
    }

    // ── OutboxPublisher: send ─────────────────────────────────────────────────

    @Test
    void publisherSendsTheStoredJsonUnchangedThenMarksItSent() throws Exception {
        UUID eventId = UUID.randomUUID();
        String json = objectMapper.writeValueAsString(sampleEvent(eventId));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(completed());

        realPublisher().publish(rowFor(eventId, json));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("tx-key"), body.capture());
        assertThat(body.getValue()).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) body.getValue()).get("eventId").asText()).isEqualTo(eventId.toString());
        verify(outboxRepository).markSent(eq(eventId), any(Instant.class));
    }

    @Test
    void publisherTreatsAnUnreachableBrokerAsTransportFailureAndDoesNotMarkSent() {
        UUID eventId = UUID.randomUUID();
        CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker down"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failed);

        assertThatThrownBy(() -> realPublisher().publish(rowFor(eventId, "{}")))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(OutboxRowException.class)
                .hasMessageContaining(eventId.toString());
        verify(outboxRepository, never()).markSent(any(), any());
    }

    @Test
    void publisherClassifiesARejectedRecordAsARowFailure() {
        UUID eventId = UUID.randomUUID();
        CompletableFuture<SendResult<String, Object>> tooLarge = new CompletableFuture<>();
        tooLarge.completeExceptionally(new RecordTooLargeException("too big"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(tooLarge);

        assertThatThrownBy(() -> realPublisher().publish(rowFor(eventId, "{}")))
                .isInstanceOf(OutboxRowException.class)
                .hasMessageContaining("broker rejected the record");
        verify(outboxRepository, never()).markSent(any(), any());
    }

    @Test
    void publisherClassifiesASerializationErrorAsARowFailure() {
        UUID eventId = UUID.randomUUID();
        CompletableFuture<SendResult<String, Object>> bad = new CompletableFuture<>();
        bad.completeExceptionally(new SerializationException("bad bytes"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(bad);

        assertThatThrownBy(() -> realPublisher().publish(rowFor(eventId, "{}")))
                .isInstanceOf(OutboxRowException.class);
    }

    @Test
    void publisherTreatsAFutureWithoutCauseAsTransportFailure() throws Exception {
        UUID eventId = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, Object>> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new ExecutionException(null));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        assertThatThrownBy(() -> realPublisher().publish(rowFor(eventId, "{}")))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(OutboxRowException.class);
    }

    @Test
    void publisherReportsTimeoutAsTransportFailure() throws Exception {
        UUID eventId = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, Object>> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException("slow"));
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(future);

        assertThatThrownBy(() -> realPublisher().publish(rowFor(eventId, "{}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out");
        verify(outboxRepository, never()).markSent(any(), any());
    }

    @Test
    void publisherReportsInterruptionAndRestoresTheFlag() {
        UUID eventId = UUID.randomUUID();
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
        OutboxPublisher publisher = realPublisher();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publish(rowFor(eventId, "{}")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(outboxRepository, never()).markSent(any(), any());
    }

    @Test
    void publisherRejectsRowWithInvalidJsonAsARowFailure() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> realPublisher().publish(rowFor(eventId, "not json")))
                .isInstanceOf(OutboxRowException.class)
                .hasMessageContaining("not valid JSON");
        verifyNoInteractions(kafkaTemplate);
    }

    // ── OutboxPublisher: after commit and failure accounting ──────────────────

    @Test
    void recordFailureCountsAnAttemptBelowTheLimit() {
        UUID eventId = UUID.randomUUID();

        boolean failed = realPublisher().recordFailure(rowFor(eventId, "{}"), "bad row", 3);

        assertThat(failed).isFalse();
        verify(outboxRepository).recordAttempt(eventId, 1, "bad row");
        verify(outboxRepository, never()).markFailed(any(), anyInt(), any(), any());
    }

    @Test
    void recordFailureMarksTheRowFailedAtTheLimit() {
        UUID eventId = UUID.randomUUID();
        OutboxEvent row = rowFor(eventId, "{}");
        setAttempts(row, 2); // two earlier failed attempts; this one is the third (the limit)

        boolean failed = realPublisher().recordFailure(row, "still bad", 3);

        assertThat(failed).isTrue();
        verify(outboxRepository).markFailed(eq(eventId), eq(3), eq("still bad"), any(Instant.class));
        verify(outboxRepository, never()).recordAttempt(any(), anyInt(), any());
    }

    @Test
    void recordFailureTruncatesLongErrorsAndKeepsNullErrors() {
        UUID eventId = UUID.randomUUID();
        OutboxPublisher publisher = realPublisher();

        publisher.recordFailure(rowFor(eventId, "{}"), "e".repeat(600), 5);
        publisher.recordFailure(rowFor(eventId, "{}"), null, 5);

        ArgumentCaptor<String> errors = ArgumentCaptor.forClass(String.class);
        verify(outboxRepository, times(2)).recordAttempt(eq(eventId), eq(1), errors.capture());
        assertThat(errors.getAllValues().get(0)).hasSize(512);
        assertThat(errors.getAllValues().get(1)).isNull();
    }

    // ── OutboxRelay ───────────────────────────────────────────────────────────

    private OutboxRelay relay(boolean enabled, SimpleMeterRegistry registry) {
        return new OutboxRelay(outboxRepository, outboxPublisher, registry, 3, enabled);
    }

    @Test
    void relayPublishesEveryUnsentRowOldestFirst() {
        OutboxRelay relay = relay(true, new SimpleMeterRegistry());
        OutboxEvent first = rowForTx(UUID.randomUUID(), "tx-1");
        OutboxEvent second = rowForTx(UUID.randomUUID(), "tx-2");
        when(outboxRepository.findTop100BySentAtIsNullAndFailedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(first, second));

        int sent = relay.relayBatch();

        assertThat(sent).isEqualTo(2);
        verify(outboxPublisher).publish(first);
        verify(outboxPublisher).publish(second);
    }

    @Test
    void relayStopsThePassOnATransportFailureWithoutCountingAnAttempt() {
        OutboxRelay relay = relay(true, new SimpleMeterRegistry());
        OutboxEvent first = rowForTx(UUID.randomUUID(), "tx-1");
        OutboxEvent second = rowForTx(UUID.randomUUID(), "tx-2");
        when(outboxRepository.findTop100BySentAtIsNullAndFailedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("broker down")).when(outboxPublisher).publish(first);

        int sent = relay.relayBatch();

        assertThat(sent).isZero();
        verify(outboxPublisher, never()).publish(second);
        verify(outboxPublisher, never()).recordFailure(any(), any(), anyInt());
    }

    @Test
    void aRowFailureBlocksOnlyLaterEventsOfTheSameTransaction() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxRelay relay = relay(true, registry);
        OutboxEvent bad = rowForTx(UUID.randomUUID(), "tx-1");
        OutboxEvent sameTx = rowForTx(UUID.randomUUID(), "tx-1");
        OutboxEvent otherTx = rowForTx(UUID.randomUUID(), "tx-2");
        when(outboxRepository.findTop100BySentAtIsNullAndFailedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(bad, sameTx, otherTx));
        doThrow(new OutboxRowException(bad, "not valid JSON", null)).when(outboxPublisher).publish(bad);

        int sent = relay.relayBatch();

        assertThat(sent).isEqualTo(1);
        verify(outboxPublisher, never()).publish(sameTx);
        verify(outboxPublisher).publish(otherTx);
        verify(outboxPublisher).recordFailure(eq(bad), contains("not valid JSON"), eq(3));
        assertThat(registry.counter("transaction.outbox.failed").count()).isZero();
    }

    @Test
    void aRowThatReachesTheAttemptLimitIsMarkedFailedAndCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxRelay relay = relay(true, registry);
        OutboxEvent bad = rowForTx(UUID.randomUUID(), "tx-1");
        OutboxEvent other = rowForTx(UUID.randomUUID(), "tx-2");
        when(outboxRepository.findTop100BySentAtIsNullAndFailedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(bad, other));
        doThrow(new OutboxRowException(bad, "too big", null)).when(outboxPublisher).publish(bad);
        when(outboxPublisher.recordFailure(eq(bad), any(), eq(3))).thenReturn(true);

        int sent = relay.relayBatch();

        assertThat(sent).isEqualTo(1);
        assertThat(registry.counter("transaction.outbox.failed").count()).isEqualTo(1.0);
        verify(outboxPublisher).publish(other);
    }

    @Test
    void scheduledPassDoesNothingWhenDisabled() {
        OutboxRelay relay = relay(false, new SimpleMeterRegistry());

        relay.relayPending();

        verifyNoInteractions(outboxRepository, outboxPublisher);
    }

    @Test
    void scheduledPassRunsTheBatchWhenEnabled() {
        OutboxRelay relay = relay(true, new SimpleMeterRegistry());
        when(outboxRepository.findTop100BySentAtIsNullAndFailedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of());

        relay.relayPending();

        verify(outboxRepository).findTop100BySentAtIsNullAndFailedAtIsNullOrderByCreatedAtAsc();
    }
}
