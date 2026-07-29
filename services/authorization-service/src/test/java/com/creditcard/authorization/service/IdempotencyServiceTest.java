package com.creditcard.authorization.service;

import com.creditcard.authorization.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private IdempotencyService idempotencyService;

    @Test
    void shouldReturnFalseAndInsertWhenKeyIsNew() {
        String key = "auth:TransactionInitiated:evt-001";
        when(processedEventRepository.insertIfAbsent(key)).thenReturn(1);

        boolean duplicate = idempotencyService.isDuplicate(key);

        assertThat(duplicate).isFalse();
        verify(processedEventRepository).insertIfAbsent(key);
    }

    @Test
    void shouldReturnTrueWhenKeyAlreadyProcessed() {
        String key = "auth:TransactionInitiated:evt-002";
        when(processedEventRepository.insertIfAbsent(key)).thenReturn(0);

        boolean duplicate = idempotencyService.isDuplicate(key);

        assertThat(duplicate).isTrue();
    }

    @Test
    void shouldBuildIdempotencyKey() {
        String key = idempotencyService.buildKey("TransactionInitiated", "evt-xyz");
        assertThat(key).isEqualTo("auth:TransactionInitiated:evt-xyz");
    }
}
