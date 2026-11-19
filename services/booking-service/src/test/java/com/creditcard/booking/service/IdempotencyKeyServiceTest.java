package com.creditcard.booking.service;

import com.creditcard.booking.domain.IdempotencyKey;
import com.creditcard.booking.repository.IdempotencyKeyRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    void claimOfNewKeyReturnsEmptyAndExpiresAfterTtl() {
        when(repository.insertIfAbsent(eq("k1"), any(), any())).thenReturn(1);

        assertThat(service.claimOrReplay("k1")).isEmpty();

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteIfExpired(eq("k1"), any());
        verify(repository).insertIfAbsent(eq("k1"), now.capture(), expiresAt.capture());
        assertThat(Duration.between(now.getValue(), expiresAt.getValue())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void claimOfCompletedKeyReturnsStoredResult() {
        UUID bookingId = UUID.randomUUID();
        when(repository.insertIfAbsent(eq("k1"), any(), any())).thenReturn(0);
        IdempotencyKey stored = new IdempotencyKey();
        ReflectionTestUtils.setField(stored, "resourceId", bookingId);
        ReflectionTestUtils.setField(stored, "responseStatus", "HELD");
        when(repository.findById("k1")).thenReturn(Optional.of(stored));

        Optional<IdempotencyKeyService.StoredResult> result = service.claimOrReplay("k1");

        assertThat(result).contains(new IdempotencyKeyService.StoredResult(bookingId, "HELD"));
    }

    @Test
    void recordResultWritesIdAndStatusToTheKey() {
        UUID bookingId = UUID.randomUUID();

        service.recordResult("k1", bookingId, "HELD");

        verify(repository).recordResult("k1", bookingId, "HELD");
    }

    @Test
    void purgeDeletesExpiredRows() {
        service.purgeExpired();

        verify(repository).purgeExpired(any());
    }
}
