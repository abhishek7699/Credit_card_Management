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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationResultListenerTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private Acknowledgment ack;

    private AuthorizationResultListener listener;

    @BeforeEach
    void setUp() {
        listener = new AuthorizationResultListener(transactionService, idempotencyService);
        // lenient: tests that override this stub would otherwise fail under strict stubs.
        lenient().doAnswer(inv -> { ((Runnable) inv.getArgument(1)).run(); return null; })
                .when(idempotencyService).recordAndExecute(anyString(), any(Runnable.class));
    }

    @Test
    void listensOnAuthorizationResultTopic() {
        assertThat(AuthorizationResultListener.TOPIC).isEqualTo("transaction.authorization.result");
    }

    @Test
    void passesOutcomeAndReasonToTheTransactionService() {
        String txId = UUID.randomUUID().toString();

        listener.onAuthorizationResult(buildEvent("TransactionDeclined", txId, "DECLINED", "51"), ack);

        verify(transactionService).applyAuthorizationResult(txId, "DECLINED", "51");
    }

    @Test
    void usesEventTypeAndEventIdAsIdempotencyKey() {
        StatusUpdateEvent event = buildEvent("TransactionAuthorized", UUID.randomUUID().toString(), "AUTHORIZED", null);

        listener.onAuthorizationResult(event, ack);

        verify(idempotencyService).recordAndExecute(eq("tx:TransactionAuthorized:" + event.getEventId()), any(Runnable.class));
    }

    @Test
    void acknowledgesAfterProcessing() {
        listener.onAuthorizationResult(buildEvent("TransactionFailed", UUID.randomUUID().toString(), "FAILED", "X"), ack);

        verify(ack).acknowledge();
    }

    @Test
    void doesNotAcknowledgeWhenIdempotencyServiceThrows() {
        doThrow(new RuntimeException("DB error"))
                .when(idempotencyService).recordAndExecute(anyString(), any(Runnable.class));

        try {
            listener.onAuthorizationResult(buildEvent("TransactionAuthorized", UUID.randomUUID().toString(), "AUTHORIZED", null), ack);
        } catch (RuntimeException ignored) {}

        verify(ack, never()).acknowledge();
    }

    private StatusUpdateEvent buildEvent(String eventType, String transactionId, String outcome, String reasonCode) {
        StatusUpdateEvent.Payload payload = new StatusUpdateEvent.Payload();
        payload.setTransactionId(transactionId);
        payload.setOutcome(outcome);
        payload.setReasonCode(reasonCode);

        StatusUpdateEvent event = new StatusUpdateEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(eventType);
        event.setSourceService("authorization-service");
        event.setOccurredAt(Instant.now());
        event.setPayload(payload);
        return event;
    }
}
