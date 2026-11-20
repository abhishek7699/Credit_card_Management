package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.event.TransactionInitiatedEvent;
import com.creditcard.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionLookupByKeyTest {

    @Mock
    private TokenizationService tokenizationService;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private KafkaTemplate<String, TransactionInitiatedEvent> kafkaTemplate;

    @Mock
    private OutboxService outboxService;

    @Mock
    private IdempotencyKeyService idempotencyKeyService;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(tokenizationService, transactionRepository, kafkaTemplate,
                outboxService, idempotencyKeyService);
    }

    @Test
    void returnsTheTransactionCreatedUnderTheKey() {
        UUID transactionId = UUID.randomUUID();
        Transaction transaction = mock(Transaction.class);
        when(idempotencyKeyService.find("attempt-1"))
                .thenReturn(Optional.of(new IdempotencyKeyService.StoredResult(transactionId, "PENDING")));
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(transaction));

        assertThat(transactionService.findByIdempotencyKey("attempt-1")).contains(transaction);
    }

    @Test
    void returnsEmptyWithoutTouchingTransactionsWhenNoCreationExists() {
        when(idempotencyKeyService.find("never-used")).thenReturn(Optional.empty());

        assertThat(transactionService.findByIdempotencyKey("never-used")).isEmpty();
        verifyNoInteractions(transactionRepository);
    }
}
