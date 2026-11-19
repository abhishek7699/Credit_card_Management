package com.creditcard.booking.service;

import com.creditcard.booking.domain.IdempotencyKey;
import com.creditcard.booking.repository.IdempotencyKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Request-level idempotency for POST /api/v1/bookings. Both methods must run inside the
 * caller's transaction (BookingService.holdBooking): the key claim then commits or rolls
 * back together with the booking it guards. A concurrent request with the same key blocks
 * on the claim until the first commits, then replays its stored result.
 */
@Service
public class IdempotencyKeyService {

    /** The stored outcome of the first successful request for a key. */
    public record StoredResult(UUID resourceId, String status) {
    }

    private final IdempotencyKeyRepository repository;
    private final Duration ttl;

    public IdempotencyKeyService(IdempotencyKeyRepository repository,
                                 @Value("${idempotency.ttl:PT24H}") Duration ttl) {
        this.repository = repository;
        this.ttl = ttl;
    }

    /**
     * Claims the key. Returns empty when this request now owns the key and must do the work
     * then call {@link #recordResult}. Returns the stored result when the key was already
     * completed, in which case the work must not run again.
     */
    @Transactional
    public Optional<StoredResult> claimOrReplay(String key) {
        Instant now = Instant.now();
        repository.deleteIfExpired(key, now);
        if (repository.insertIfAbsent(key, now, now.plus(ttl)) == 0) {
            IdempotencyKey stored = repository.findById(key).orElseThrow(IllegalStateException::new);
            return Optional.of(new StoredResult(stored.getResourceId(), stored.getResponseStatus()));
        }
        return Optional.empty();
    }

    @Transactional
    public void recordResult(String key, UUID resourceId, String status) {
        repository.recordResult(key, resourceId, status);
    }

    /** Deletes keys past their expiry so the table does not grow without bound. */
    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    @Transactional
    public void purgeExpired() {
        repository.purgeExpired(Instant.now());
    }
}
