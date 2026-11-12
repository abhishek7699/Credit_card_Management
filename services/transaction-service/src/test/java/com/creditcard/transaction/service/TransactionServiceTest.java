package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.event.TransactionInitiatedEvent;
import com.creditcard.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TokenizationService tokenizationService;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private KafkaTemplate<String, TransactionInitiatedEvent> kafkaTemplate;

    @Mock
    private OutboxService outboxService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(tokenizationService, transactionRepository, kafkaTemplate, outboxService);
    }

    @Test
    void shouldCallTokenizationServiceWithRawPan() {
        String rawPan = "4111111111111111";
        when(tokenizationService.tokenize(rawPan))
                .thenReturn(new TokenResult("vault:v1:abc123", "****-****-****-1111"));
        Transaction savedTx = buildSavedTransaction();
        when(transactionRepository.save(any())).thenReturn(savedTx);

        transactionService.initiateTransaction(rawPan, "Jane Doe", new BigDecimal("50.00"), "USD", "merchant-001");

        verify(tokenizationService).tokenize(rawPan);
    }

    @Test
    void shouldSaveTransactionWithPendingStatusAndToken() {
        when(tokenizationService.tokenize(any()))
                .thenReturn(new TokenResult("vault:v1:token123", "****-****-****-4321"));
        Transaction savedTx = buildSavedTransaction();
        when(transactionRepository.save(any())).thenReturn(savedTx);

        transactionService.initiateTransaction("4321432143214321", "John Smith", new BigDecimal("100.00"), "EUR", "merchant-001");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();

        assertThat(saved.getCardToken()).isEqualTo("vault:v1:token123");
        assertThat(saved.getMaskedCardNumber()).isEqualTo("****-****-****-4321");
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(saved.getCardholderName()).isEqualTo("John Smith");
        assertThat(saved.getAmount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(saved.getCurrencyCode()).isEqualTo("EUR");
    }

    @Test
    void shouldSetMerchantIdNullWhenBlank() {
        when(tokenizationService.tokenize(any()))
                .thenReturn(new TokenResult("vault:v1:token", "****-****-****-0001"));
        when(transactionRepository.save(any())).thenReturn(buildSavedTransaction());

        transactionService.initiateTransaction("4111111111111111", "Jane Doe", new BigDecimal("10.00"), "USD", "");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getMerchantId()).isNull();
    }

    @Test
    void savedEntityShouldNotContainRawPan() {
        String rawPan = "4111111111111111";
        when(tokenizationService.tokenize(rawPan))
                .thenReturn(new TokenResult("vault:v1:opaque", "****-****-****-1111"));
        when(transactionRepository.save(any())).thenReturn(buildSavedTransaction());

        transactionService.initiateTransaction(rawPan, "Jane Doe", new BigDecimal("10.00"), "USD", "merchant-001");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());

        assertThat(captor.getValue().getCardToken()).doesNotContain(rawPan);
        assertThat(captor.getValue().getMaskedCardNumber()).doesNotContain(rawPan);
    }

    @Test
    void shouldPublishTransactionInitiatedEventToKafka() {
        when(tokenizationService.tokenize(any()))
                .thenReturn(new TokenResult("vault:v1:token123", "****-****-****-9999"));
        Transaction savedTx = buildSavedTransaction();
        when(transactionRepository.save(any())).thenReturn(savedTx);

        transactionService.initiateTransaction("4999999999999999", "Alice", new BigDecimal("25.00"), "GBP", "merchant-001");

        ArgumentCaptor<TransactionInitiatedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionInitiatedEvent.class);
        verify(kafkaTemplate).send(eq("transaction.initiated"), any(String.class), eventCaptor.capture());

        TransactionInitiatedEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo("TransactionInitiated");
        assertThat(event.getPayload().getTransactionId()).isEqualTo(savedTx.getId().toString());
        assertThat(event.getPayload().getCardToken()).startsWith("vault:");
        assertThat(event.getPayload().getMaskedCardNumber()).matches("\\*{4}-\\*{4}-\\*{4}-\\d{4}");
        assertThat(event.getPayload().getMerchantId()).isEqualTo("merchant-001");
    }

    @Test
    void kafkaEventShouldNotContainRawPan() {
        String rawPan = "4111111111111111";
        when(tokenizationService.tokenize(rawPan))
                .thenReturn(new TokenResult("vault:v1:opaque", "****-****-****-1111"));
        when(transactionRepository.save(any())).thenReturn(buildSavedTransaction());

        transactionService.initiateTransaction(rawPan, "Jane Doe", new BigDecimal("10.00"), "USD", "merchant-001");

        ArgumentCaptor<TransactionInitiatedEvent> eventCaptor = ArgumentCaptor.forClass(TransactionInitiatedEvent.class);
        verify(kafkaTemplate).send(any(), any(), eventCaptor.capture());

        TransactionInitiatedEvent event = eventCaptor.getValue();
        assertThat(event.getPayload().getCardToken()).doesNotContain(rawPan);
        assertThat(event.getPayload().getMaskedCardNumber()).doesNotContain(rawPan);
    }

    @Test
    void shouldReturnTransactionId() {
        when(tokenizationService.tokenize(any()))
                .thenReturn(new TokenResult("vault:v1:t", "****-****-****-0000"));
        Transaction savedTx = buildSavedTransaction();
        when(transactionRepository.save(any())).thenReturn(savedTx);

        UUID result = transactionService.initiateTransaction("4000000000000000", "X", new BigDecimal("1.00"), "USD", "merchant-001");

        assertThat(result).isEqualTo(savedTx.getId());
    }

    @Test
    void shouldNotSaveTransactionWhenVaultFails() {
        when(tokenizationService.tokenize(any()))
                .thenThrow(new RuntimeException("Vault unavailable"));

        assertThatThrownBy(() ->
                transactionService.initiateTransaction("4111111111111111", "Jane", new BigDecimal("10.00"), "USD", "merchant-001"))
                .isInstanceOf(RuntimeException.class);

        verify(transactionRepository, never()).save(any());
    }

    // ── getTransaction ─────────────────────────────────────────────────────────

    @Test
    void getTransactionShouldReturnTransactionById() {
        Transaction tx = buildTransactionWithStatus(TransactionStatus.AUTHORIZED);
        when(transactionRepository.findById(tx.getId())).thenReturn(Optional.of(tx));

        Optional<Transaction> result = transactionService.getTransaction(tx.getId().toString());

        assertThat(result).isPresent().contains(tx);
    }

    @Test
    void getTransactionShouldReturnEmptyForUnknownId() {
        UUID unknownId = UUID.randomUUID();
        when(transactionRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThat(transactionService.getTransaction(unknownId.toString())).isEmpty();
    }

    // ── US3: handleFailed ─────────────────────────────────────────────────────

    // ── 013: conditional final-outcome transitions, written to the outbox ─────

    private Transaction buildSavedTransaction() {
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());
        tx.setCardToken("vault:v1:savedtoken");
        tx.setMaskedCardNumber("****-****-****-0000");
        tx.setCardholderName("Jane Doe");
        tx.setAmount(new BigDecimal("50.00"));
        tx.setCurrencyCode("USD");
        tx.setMerchantId("merchant-001");
        tx.setStatus(TransactionStatus.PENDING);
        return tx;
    }

    private Transaction buildTransactionWithStatus(TransactionStatus status) {
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());
        tx.setCardToken("vault:v1:tok");
        tx.setMaskedCardNumber("****-****-****-0000");
        tx.setCardholderName("Test User");
        tx.setAmount(new BigDecimal("50.00"));
        tx.setCurrencyCode("USD");
        tx.setStatus(status);
        return tx;
    }

    private Transaction pendingTransaction(UUID id) {
        Transaction tx = new Transaction();
        tx.setCardToken("vault:v1:token");
        tx.setStatus(TransactionStatus.PENDING);
        try {
            var f = Transaction.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(tx, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return tx;
    }

    @Test
    void winningAuthorizedTransitionWritesFinalEventToOutbox() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.of(pendingTransaction(id)));
        when(transactionRepository.transitionStatus(eq(id), eq(TransactionStatus.PENDING),
                eq(TransactionStatus.AUTHORIZED), any())).thenReturn(1);

        transactionService.applyAuthorizationResult(id.toString(), "AUTHORIZED", null);

        ArgumentCaptor<com.creditcard.transaction.event.StatusUpdateEvent> captor =
                ArgumentCaptor.forClass(com.creditcard.transaction.event.StatusUpdateEvent.class);
        verify(outboxService).append(eq("transaction.status.updated"),
                eq(TransactionService.stableEventId(id, "TransactionAuthorized")), eq(id.toString()), captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(captor.getValue().getEventId())
                .isEqualTo(TransactionService.stableEventId(id, "TransactionAuthorized").toString());
        assertThat(captor.getValue().getPayload().getOutcome()).isEqualTo("AUTHORIZED");
        assertThat(captor.getValue().getPayload().getCardToken()).isEqualTo("vault:v1:token");
    }

    @Test
    void winningDeclinedAndFailedTransitionsWriteTheirOwnEventTypes() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.of(pendingTransaction(id)));
        when(transactionRepository.transitionStatus(eq(id), eq(TransactionStatus.PENDING), any(), any())).thenReturn(1);

        transactionService.applyAuthorizationResult(id.toString(), "DECLINED", "51");
        transactionService.applyAuthorizationResult(id.toString(), "FAILED", "INVALID_EVENT_PAYLOAD");

        verify(outboxService).append(eq("transaction.status.updated"),
                eq(TransactionService.stableEventId(id, "TransactionDeclined")), eq(id.toString()), any());
        verify(outboxService).append(eq("transaction.status.updated"),
                eq(TransactionService.stableEventId(id, "TransactionFailed")), eq(id.toString()), any());
    }

    @Test
    void losingTransitionWritesNothingToOutbox() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.of(pendingTransaction(id)));
        when(transactionRepository.transitionStatus(eq(id), eq(TransactionStatus.PENDING), any(), any())).thenReturn(0);

        transactionService.applyAuthorizationResult(id.toString(), "AUTHORIZED", null);

        verify(outboxService, never()).append(any(), any(), any(), any());
    }

    @Test
    void unknownOutcomeIsIgnoredWithoutTouchingTheRow() {
        UUID id = UUID.randomUUID();

        transactionService.applyAuthorizationResult(id.toString(), "PENDING", null);
        transactionService.applyAuthorizationResult(id.toString(), null, null);

        verify(transactionRepository, never()).transitionStatus(any(), any(), any(), any());
        verify(outboxService, never()).append(any(), any(), any(), any());
    }

    @Test
    void resultForUnknownTransactionIsIgnored() {
        UUID id = UUID.randomUUID();
        when(transactionRepository.findById(id)).thenReturn(Optional.empty());

        transactionService.applyAuthorizationResult(id.toString(), "AUTHORIZED", null);

        verify(transactionRepository, never()).transitionStatus(any(), any(), any(), any());
        verify(outboxService, never()).append(any(), any(), any(), any());
    }

    @Test
    void stableEventIdIsDeterministicAndDependsOnStatus() {
        UUID id = UUID.randomUUID();

        assertThat(TransactionService.stableEventId(id, "TransactionAuthorized"))
                .isEqualTo(TransactionService.stableEventId(id, "TransactionAuthorized"));
        assertThat(TransactionService.stableEventId(id, "TransactionAuthorized"))
                .isNotEqualTo(TransactionService.stableEventId(id, "TransactionDeclined"));
        assertThat(TransactionService.stableEventId(id, "TransactionAuthorized"))
                .isNotEqualTo(TransactionService.stableEventId(UUID.randomUUID(), "TransactionAuthorized"));
    }

    @Test
    void eventTypeIsDefinedOnlyForFinalStatuses() {
        assertThatThrownBy(() -> TransactionService.eventTypeFor(TransactionStatus.PENDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a final status");
    }

    @Test
    void finalStatusMappingCoversOnlyTheThreeFinalOutcomes() {
        assertThat(TransactionService.finalStatusFor("AUTHORIZED")).isEqualTo(TransactionStatus.AUTHORIZED);
        assertThat(TransactionService.finalStatusFor("DECLINED")).isEqualTo(TransactionStatus.DECLINED);
        assertThat(TransactionService.finalStatusFor("FAILED")).isEqualTo(TransactionStatus.FAILED);
        assertThat(TransactionService.finalStatusFor("PENDING")).isNull();
        assertThat(TransactionService.finalStatusFor(null)).isNull();
    }
}
