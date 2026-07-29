package com.creditcard.transaction.service;

import com.creditcard.transaction.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(processedEventRepository);
    }

    @Test
    void shouldExecuteBusinessLogicWhenInsertSucceeds() {
        String eventKey = "tx:TransactionAuthorized:550e8400-e29b-41d4-a716-446655440000";
        when(processedEventRepository.insertIfAbsent(eventKey)).thenReturn(1);

        AtomicInteger callCount = new AtomicInteger(0);
        idempotencyService.recordAndExecute(eventKey, callCount::incrementAndGet);

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void shouldCallInsertIfAbsentWithCorrectKey() {
        String eventKey = "tx:TransactionAuthorized:550e8400-e29b-41d4-a716-446655440000";
        when(processedEventRepository.insertIfAbsent(eventKey)).thenReturn(1);

        idempotencyService.recordAndExecute(eventKey, () -> {});

        verify(processedEventRepository).insertIfAbsent(eventKey);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void shouldSkipBusinessLogicWhenInsertConflicts() {
        String eventKey = "tx:TransactionAuthorized:550e8400-e29b-41d4-a716-446655440000";
        when(processedEventRepository.insertIfAbsent(eventKey)).thenReturn(0);

        AtomicInteger callCount = new AtomicInteger(0);
        idempotencyService.recordAndExecute(eventKey, callCount::incrementAndGet);

        assertThat(callCount.get()).isZero();
    }

    @Test
    void isAlreadyProcessedShouldDelegateToRepository() {
        String eventKey = "tx:TransactionDeclined:aabbccdd-0000-0000-0000-000000000000";
        when(processedEventRepository.existsByEventKey(eventKey)).thenReturn(true);

        assertThat(idempotencyService.isAlreadyProcessed(eventKey)).isTrue();
    }

    @Test
    void isAlreadyProcessedReturnsFalseForUnseenEvent() {
        String eventKey = "tx:TransactionFailed:unknown-id";
        when(processedEventRepository.existsByEventKey(eventKey)).thenReturn(false);

        assertThat(idempotencyService.isAlreadyProcessed(eventKey)).isFalse();
    }
}
