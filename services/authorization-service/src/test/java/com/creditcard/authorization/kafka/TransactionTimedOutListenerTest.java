package com.creditcard.authorization.kafka;

import com.creditcard.authorization.kafka.event.TransactionTimedOutEvent;
import com.creditcard.authorization.service.CreditReleaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class TransactionTimedOutListenerTest {

    @Mock
    private CreditReleaseService creditReleaseService;

    @Mock
    private Acknowledgment ack;

    private TransactionTimedOutListener listener() {
        return new TransactionTimedOutListener(creditReleaseService);
    }

    private static TransactionTimedOutEvent event(String eventId, UUID txId, String cardToken) {
        TransactionTimedOutEvent.Payload payload = new TransactionTimedOutEvent.Payload();
        payload.setTransactionId(txId);
        payload.setCardToken(cardToken);
        TransactionTimedOutEvent event = new TransactionTimedOutEvent();
        event.setEventId(eventId);
        event.setEventType("TransactionTimedOut");
        event.setPayload(payload);
        return event;
    }

    @Test
    void releasesThenAcknowledges() {
        UUID txId = UUID.randomUUID();

        listener().onTransactionTimedOut(event("evt-1", txId, "vault:v1:tok"), ack);

        verify(creditReleaseService).release("evt-1", txId, "vault:v1:tok");
        verify(ack).acknowledge();
    }

    @Test
    void aNullRecordIsDiscardedAndAcknowledged() {
        listener().onTransactionTimedOut(null, ack);

        verifyNoInteractions(creditReleaseService);
        verify(ack).acknowledge();
    }

    @Test
    void aRecordWithoutEventIdIsDiscardedAndAcknowledged() {
        listener().onTransactionTimedOut(event(null, UUID.randomUUID(), "vault:v1:tok"), ack);

        verifyNoInteractions(creditReleaseService);
        verify(ack).acknowledge();
    }

    @Test
    void aRecordWithoutPayloadIsDiscardedAndAcknowledged() {
        TransactionTimedOutEvent noPayload = event("evt-2", UUID.randomUUID(), "vault:v1:tok");
        noPayload.setPayload(null);

        listener().onTransactionTimedOut(noPayload, ack);

        verifyNoInteractions(creditReleaseService);
        verify(ack).acknowledge();
    }

    @Test
    void aFailedReleaseIsNotAcknowledgedSoTheEventIsRedelivered() {
        UUID txId = UUID.randomUUID();
        doThrow(new IllegalStateException("database unavailable"))
                .when(creditReleaseService).release(anyString(), any(), any());

        assertThatThrownBy(() -> listener().onTransactionTimedOut(event("evt-3", txId, "vault:v1:tok"), ack))
                .isInstanceOf(IllegalStateException.class);
        verify(ack, never()).acknowledge();
    }
}
