package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.IdempotencyKey;
import com.creditcard.transaction.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyKeyServiceTest {

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyKeyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyKeyService(repository, Duration.ofHours(24));
    }

    @Test
    void nullKeyRunsCreationWithoutTouchingTheKeyTable() {
        UUID id = UUID.randomUUID();
        Supplier<IdempotencyKeyService.StoredResult> creation =
                () -> new IdempotencyKeyService.StoredResult(id, "PENDING");

        IdempotencyKeyService.StoredResult result = service.executeOnce(null, creation);

        assertThat(result.resourceId()).isEqualTo(id);
        verifyNoInteractions(repository);
    }

    @Test
    void newKeyClaimsRunsCreationAndRecordsResult() {
        UUID id = UUID.randomUUID();
        when(repository.insertIfAbsent(eq("k1"), any(), any())).thenReturn(1);

        IdempotencyKeyService.StoredResult result =
                service.executeOnce("k1", () -> new IdempotencyKeyService.StoredResult(id, "PENDING"));

        assertThat(result).isEqualTo(new IdempotencyKeyService.StoredResult(id, "PENDING"));
        verify(repository).deleteIfExpired(eq("k1"), any());
        verify(repository).recordResult("k1", id, "PENDING");
    }

    @Test
    void claimExpiresAtIsTtlAfterNow() {
        when(repository.insertIfAbsent(eq("k1"), any(), any())).thenReturn(1);

        service.executeOnce("k1", () -> new IdempotencyKeyService.StoredResult(UUID.randomUUID(), "PENDING"));

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).insertIfAbsent(eq("k1"), now.capture(), expiresAt.capture());
        assertThat(Duration.between(now.getValue(), expiresAt.getValue())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void existingKeyReplaysStoredResultWithoutRunningCreation() {
        UUID original = UUID.randomUUID();
        when(repository.insertIfAbsent(eq("k1"), any(), any())).thenReturn(0);
        IdempotencyKey stored = new IdempotencyKey();
        ReflectionTestUtils.setField(stored, "resourceId", original);
        ReflectionTestUtils.setField(stored, "responseStatus", "PENDING");
        when(repository.findById("k1")).thenReturn(Optional.of(stored));
        Supplier<IdempotencyKeyService.StoredResult> creation = () -> {
            throw new AssertionError("creation must not run on replay");
        };

        IdempotencyKeyService.StoredResult result = service.executeOnce("k1", creation);

        assertThat(result).isEqualTo(new IdempotencyKeyService.StoredResult(original, "PENDING"));
        verify(repository, never()).recordResult(anyString(), any(), anyString());
    }

    @Test
    void creationFailurePropagatesAndRecordsNothing() {
        when(repository.insertIfAbsent(eq("k1"), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> service.executeOnce("k1", () -> {
            throw new IllegalStateException("Vault down");
        })).isInstanceOf(IllegalStateException.class);

        verify(repository, never()).recordResult(anyString(), any(), anyString());
    }

    @Test
    void purgeDeletesRowsPastExpiry() {
        service.purgeExpired();

        verify(repository).purgeExpired(any());
    }
}
