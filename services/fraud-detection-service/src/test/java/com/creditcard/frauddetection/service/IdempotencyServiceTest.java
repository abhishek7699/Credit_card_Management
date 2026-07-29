package com.creditcard.frauddetection.service;

import com.creditcard.frauddetection.repository.ProcessedEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @InjectMocks
    private IdempotencyService idempotencyService;

    @Test
    void buildKeyShouldReturnCorrectFormat() {
        String key = idempotencyService.buildKey("TransactionInitiated", "evt-001");
        assertThat(key).isEqualTo("fraud:TransactionInitiated:evt-001");
    }

    @Test
    void isDuplicateShouldReturnFalseWhenInsertSucceeds() {
        String key = "fraud:TransactionInitiated:evt-new";
        when(processedEventRepository.insertIfAbsent(key)).thenReturn(1);

        assertThat(idempotencyService.isDuplicate(key)).isFalse();
    }

    @Test
    void isDuplicateShouldReturnTrueWhenInsertConflicts() {
        String key = "fraud:TransactionInitiated:evt-dup";
        when(processedEventRepository.insertIfAbsent(key)).thenReturn(0);

        assertThat(idempotencyService.isDuplicate(key)).isTrue();
    }
}
