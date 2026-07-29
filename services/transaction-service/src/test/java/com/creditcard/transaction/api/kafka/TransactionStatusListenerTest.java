package com.creditcard.transaction.api.kafka;

import com.creditcard.transaction.event.StatusUpdateEvent;
import com.creditcard.transaction.service.IdempotencyService;
import com.creditcard.transaction.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionStatusListenerTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private Acknowledgment ack;

    private TransactionStatusListener listener;

    @BeforeEach
    void setUp() {
        listener = new TransactionStatusListener(transactionService, idempotencyService);
        // Default: execute the Runnable so routing assertions work.
        // lenient() because tests that override this stubbing (e.g., doThrow) would
        // otherwise trigger Mockito's UnnecessaryStubbingException in strict mode.
        lenient().doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(idempotencyService).recordAndExecute(anyString(), any(Runnable.class));
    }

    @Test
    void shouldPassCorrectIdempotencyKeyForAuthorizedEvent() {
        String txId = UUID.randomUUID().toString();
        StatusUpdateEvent event = buildEvent("TransactionAuthorized", txId);
        String expectedKey = "tx:TransactionAuthorized:" + event.getEventId();

        listener.onStatusUpdate(event, ack);

        verify(idempotencyService).recordAndExecute(eq(expectedKey), any(Runnable.class));
    }

    @Test
    void shouldRouteAuthorizedEventToHandleAuthorized() {
        String txId = UUID.randomUUID().toString();
        StatusUpdateEvent event = buildEvent("TransactionAuthorized", txId);

        listener.onStatusUpdate(event, ack);

        verify(transactionService).handleAuthorized(txId);
        verify(transactionService, never()).handleDeclined(any());
    }

    @Test
    void shouldRouteDeclinedEventToHandleDeclined() {
        String txId = UUID.randomUUID().toString();
        StatusUpdateEvent event = buildEvent("TransactionDeclined", txId);

        listener.onStatusUpdate(event, ack);

        verify(transactionService).handleDeclined(txId);
        verify(transactionService, never()).handleAuthorized(any());
    }

    @Test
    void shouldPassCorrectIdempotencyKeyForDeclinedEvent() {
        String txId = UUID.randomUUID().toString();
        StatusUpdateEvent event = buildEvent("TransactionDeclined", txId);
        String expectedKey = "tx:TransactionDeclined:" + event.getEventId();

        listener.onStatusUpdate(event, ack);

        verify(idempotencyService).recordAndExecute(eq(expectedKey), any(Runnable.class));
    }

    @Test
    void shouldAcknowledgeKafkaOffsetAfterSuccessfulProcessing() {
        listener.onStatusUpdate(buildEvent("TransactionAuthorized", UUID.randomUUID().toString()), ack);

        verify(ack).acknowledge();
    }

    @Test
    void shouldAcknowledgeAfterDeclinedProcessing() {
        listener.onStatusUpdate(buildEvent("TransactionDeclined", UUID.randomUUID().toString()), ack);

        verify(ack).acknowledge();
    }

    // ── US3: TransactionFailed routing ────────────────────────────────────────

    @Test
    void shouldRouteFailedEventToHandleFailed() {
        String txId = UUID.randomUUID().toString();
        StatusUpdateEvent event = buildEvent("TransactionFailed", txId);

        listener.onStatusUpdate(event, ack);

        verify(transactionService).handleFailed(txId);
        verify(transactionService, never()).handleAuthorized(any());
        verify(transactionService, never()).handleDeclined(any());
    }

    @Test
    void shouldPassCorrectIdempotencyKeyForFailedEvent() {
        String txId = UUID.randomUUID().toString();
        StatusUpdateEvent event = buildEvent("TransactionFailed", txId);
        String expectedKey = "tx:TransactionFailed:" + event.getEventId();

        listener.onStatusUpdate(event, ack);

        verify(idempotencyService).recordAndExecute(eq(expectedKey), any(Runnable.class));
    }

    @Test
    void shouldAcknowledgeAfterFailedProcessing() {
        listener.onStatusUpdate(buildEvent("TransactionFailed", UUID.randomUUID().toString()), ack);

        verify(ack).acknowledge();
    }

    @Test
    void shouldLogWarningAndAcknowledgeForUnknownEventType() {
        StatusUpdateEvent event = buildEvent("UnknownEventType", UUID.randomUUID().toString());

        listener.onStatusUpdate(event, ack);

        verify(transactionService, never()).handleAuthorized(any());
        verify(transactionService, never()).handleDeclined(any());
        verify(transactionService, never()).handleFailed(any());
        verify(ack).acknowledge();
    }

    @Test
    void shouldNotAcknowledgeWhenIdempotencyServiceThrows() {
        doThrow(new RuntimeException("DB error"))
                .when(idempotencyService).recordAndExecute(anyString(), any(Runnable.class));

        try {
            listener.onStatusUpdate(buildEvent("TransactionAuthorized", UUID.randomUUID().toString()), ack);
        } catch (RuntimeException ignored) {}

        verify(ack, never()).acknowledge();
    }

    private StatusUpdateEvent buildEvent(String eventType, String transactionId) {
        StatusUpdateEvent.Payload payload = new StatusUpdateEvent.Payload();
        payload.setTransactionId(transactionId);

        StatusUpdateEvent event = new StatusUpdateEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setSourceService("authorization-service");
        event.setOccurredAt(Instant.now());
        event.setPayload(payload);
        return event;
    }
}
