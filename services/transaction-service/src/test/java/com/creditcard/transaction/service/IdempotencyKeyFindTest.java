package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.IdempotencyKey;
import com.creditcard.transaction.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyKeyFindTest {

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyKeyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyKeyService(repository, Duration.ofHours(24));
    }

    @Test
    void retryAfterAProcessedButFailedCallReplaysTheSameTransaction() {
        // The first call committed its transaction, but the caller saw UNAVAILABLE and retries
        // with the same key. The retry must replay that transaction, not create a second one.
        UUID transactionId = UUID.randomUUID();
        AtomicInteger creations = new AtomicInteger();
        Supplier<IdempotencyKeyService.StoredResult> creation = () -> {
            creations.incrementAndGet();
            return new IdempotencyKeyService.StoredResult(transactionId, "PENDING");
        };
        when(repository.insertIfAbsent(eq("attempt-1"), any(), any())).thenReturn(1, 0);
        when(repository.findById("attempt-1")).thenReturn(
                Optional.of(storedRow("attempt-1", transactionId, "PENDING", Instant.now().plusSeconds(600))));

        IdempotencyKeyService.StoredResult first = service.executeOnce("attempt-1", creation);
        IdempotencyKeyService.StoredResult retry = service.executeOnce("attempt-1", creation);

        assertThat(creations).hasValue(1);
        assertThat(first.resourceId()).isEqualTo(transactionId);
        assertThat(retry.resourceId()).isEqualTo(transactionId);
    }

    @Test
    void findReturnsEmptyWhenTheKeyWasNeverUsed() {
        when(repository.findById("unknown")).thenReturn(Optional.empty());

        assertThat(service.find("unknown")).isEmpty();
    }

    @Test
    void findReturnsEmptyWhenTheKeyHasExpired() {
        when(repository.findById("old")).thenReturn(
                Optional.of(storedRow("old", UUID.randomUUID(), "PENDING", Instant.now().minusSeconds(1))));

        assertThat(service.find("old")).isEmpty();
    }

    @Test
    void findReturnsEmptyWhenTheCreationNeverCommitted() {
        // A claimed row with no resource: the creating transaction has not committed (or rolled back).
        when(repository.findById("in-flight")).thenReturn(
                Optional.of(storedRow("in-flight", null, null, Instant.now().plusSeconds(600))));

        assertThat(service.find("in-flight")).isEmpty();
    }

    @Test
    void findReturnsTheStoredResultForALiveKey() {
        UUID transactionId = UUID.randomUUID();
        when(repository.findById("live")).thenReturn(
                Optional.of(storedRow("live", transactionId, "PENDING", Instant.now().plusSeconds(600))));

        assertThat(service.find("live"))
                .contains(new IdempotencyKeyService.StoredResult(transactionId, "PENDING"));
    }

    private static IdempotencyKey storedRow(String key, UUID resourceId, String status, Instant expiresAt) {
        IdempotencyKey row = new IdempotencyKey();
        ReflectionTestUtils.setField(row, "idempotencyKey", key);
        ReflectionTestUtils.setField(row, "resourceId", resourceId);
        ReflectionTestUtils.setField(row, "responseStatus", status);
        ReflectionTestUtils.setField(row, "expiresAt", expiresAt);
        return row;
    }
}
