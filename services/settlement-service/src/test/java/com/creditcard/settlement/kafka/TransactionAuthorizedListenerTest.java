package com.creditcard.settlement.kafka;

import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.kafka.event.TransactionAuthorizedEvent;
import com.creditcard.settlement.repository.ProcessedEventRepository;
import com.creditcard.settlement.repository.SettlementTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.support.Acknowledgment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionAuthorizedListenerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private SettlementTransactionRepository txRepository;

    @Mock
    private Acknowledgment ack;

    private TransactionAuthorizedListener listener;

    @BeforeEach
    void setUp() {
        listener = new TransactionAuthorizedListener(processedEventRepository, txRepository);
    }

    @Test
    void nullEvent_ackStillCalled_insertNeverCalled() {
        listener.onTransactionAuthorized(null, ack);

        verify(ack).acknowledge();
        verify(processedEventRepository, never()).insertIfAbsent(anyString());
        verify(txRepository, never()).save(any());
    }

    @Test
    void nullPayload_ackStillCalled_insertNeverCalled() {
        TransactionAuthorizedEvent event = new TransactionAuthorizedEvent();
        event.setEventId("evt-1");

        listener.onTransactionAuthorized(event, ack);

        verify(ack).acknowledge();
        verify(processedEventRepository, never()).insertIfAbsent(anyString());
        verify(txRepository, never()).save(any());
    }

    @Test
    void nullEventId_ackStillCalled_insertNeverCalled() {
        TransactionAuthorizedEvent event = new TransactionAuthorizedEvent();
        TransactionAuthorizedEvent.Payload payload = new TransactionAuthorizedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        event.setPayload(payload);

        listener.onTransactionAuthorized(event, ack);

        verify(ack).acknowledge();
        verify(processedEventRepository, never()).insertIfAbsent(anyString());
    }

    @Test
    void nullTransactionId_ackStillCalled_saveNeverCalled() {
        TransactionAuthorizedEvent event = new TransactionAuthorizedEvent();
        event.setEventId("evt-1");
        TransactionAuthorizedEvent.Payload payload = new TransactionAuthorizedEvent.Payload();
        event.setPayload(payload);

        listener.onTransactionAuthorized(event, ack);

        verify(ack).acknowledge();
        verify(txRepository, never()).save(any());
    }

    @Test
    void duplicateEvent_insertIfAbsentReturnsZero_saveNeverCalled() {
        TransactionAuthorizedEvent event = buildValidEvent("evt-dup", UUID.randomUUID());
        when(processedEventRepository.insertIfAbsent(anyString())).thenReturn(0);

        listener.onTransactionAuthorized(event, ack);

        verify(ack).acknowledge();
        verify(txRepository, never()).save(any());
    }

    @Test
    void validEvent_savedToRepository() {
        TransactionAuthorizedEvent event = buildValidEvent("evt-1", UUID.randomUUID());
        when(processedEventRepository.insertIfAbsent(anyString())).thenReturn(1);

        listener.onTransactionAuthorized(event, ack);

        verify(ack).acknowledge();
        verify(txRepository).save(any(SettlementTransaction.class));
    }

    @Test
    void serviceThrows_ackStillCalled_exceptionPropagates() {
        TransactionAuthorizedEvent event = buildValidEvent("evt-1", UUID.randomUUID());
        when(processedEventRepository.insertIfAbsent(anyString())).thenReturn(1);
        when(txRepository.save(any())).thenThrow(new RuntimeException("DB error"));

        assertThatThrownBy(() -> listener.onTransactionAuthorized(event, ack))
                .isInstanceOf(RuntimeException.class);
        verify(ack).acknowledge();
    }

    @Test
    void nullMerchantId_ackStillCalled_saveNeverCalled() {
        TransactionAuthorizedEvent event = new TransactionAuthorizedEvent();
        event.setEventId("evt-1");
        TransactionAuthorizedEvent.Payload payload = new TransactionAuthorizedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        // merchantId is null
        event.setPayload(payload);

        listener.onTransactionAuthorized(event, ack);

        verify(ack).acknowledge();
        verify(txRepository, never()).save(any());
    }

    @Test
    void nullCurrencyCode_defaultsToUsd() {
        TransactionAuthorizedEvent event = new TransactionAuthorizedEvent();
        event.setEventId("evt-nullccy");
        TransactionAuthorizedEvent.Payload payload = new TransactionAuthorizedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setMerchantId("merchant-1");
        payload.setCardToken("tok-abc");
        payload.setMaskedCardNumber("4111-****-****-1111");
        payload.setAmount(new BigDecimal("100.00"));
        // currencyCode intentionally left null
        payload.setAuthorizedAt(Instant.now());
        event.setPayload(payload);

        when(processedEventRepository.insertIfAbsent(anyString())).thenReturn(1);
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        listener.onTransactionAuthorized(event, ack);

        verify(ack).acknowledge();
        verify(txRepository).save(argThat(tx -> "USD".equals(((SettlementTransaction) tx).getCurrencyCode())));
    }

    private TransactionAuthorizedEvent buildValidEvent(String eventId, UUID txId) {
        TransactionAuthorizedEvent event = new TransactionAuthorizedEvent();
        event.setEventId(eventId);
        TransactionAuthorizedEvent.Payload payload = new TransactionAuthorizedEvent.Payload();
        payload.setTransactionId(txId);
        payload.setMerchantId("merchant-1");
        payload.setCardToken("tok-abc");
        payload.setMaskedCardNumber("4111-****-****-1111");
        payload.setAmount(new BigDecimal("100.00"));
        payload.setCurrencyCode("USD");
        payload.setAuthorizedAt(Instant.now());
        event.setPayload(payload);
        return event;
    }
}
