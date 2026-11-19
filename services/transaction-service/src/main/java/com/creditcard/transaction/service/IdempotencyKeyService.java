package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.IdempotencyKey;
import com.creditcard.transaction.repository.IdempotencyKeyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Request-level idempotency for InitiateTransaction. The key is claimed in the same DB
 * transaction as the creation work, and claimed BEFORE that work runs: a concurrent
 * request with the same key blocks on the insert until the first commits, then replays
 * its stored result. This keeps the Kafka transaction.initiated event from being
 * published twice, since that event drives payment downstream.
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
     * Runs {@code creation} at most once per live key. A null key runs it unconditionally,
     * exactly as before this feature existed. On replay the creation work is skipped and
     * the stored result is returned.
     */
    @Transactional
    public StoredResult executeOnce(String key, Supplier<StoredResult> creation) {
        if (key == null) {
            return creation.get();
        }
        Instant now = Instant.now();
        repository.deleteIfExpired(key, now);
        if (repository.insertIfAbsent(key, now, now.plus(ttl)) == 0) {
            IdempotencyKey stored = repository.findById(key).orElseThrow(IllegalStateException::new);
            return new StoredResult(stored.getResourceId(), stored.getResponseStatus());
        }
        StoredResult result = creation.get();
        repository.recordResult(key, result.resourceId(), result.status());
        return result;
    }

    /** Deletes keys past their expiry so the table does not grow without bound. */
    @Scheduled(fixedDelay = 60 * 60 * 1000L)
    @Transactional
    public void purgeExpired() {
        repository.purgeExpired(Instant.now());
    }
}
