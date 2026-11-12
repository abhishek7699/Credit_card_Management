package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;
import com.creditcard.transaction.repository.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Polling relay for the transactional outbox (013). It publishes only rows that were not sent
 * (rows sent right after commit are skipped), oldest first.
 *
 * <p>Ordering matters only within one transaction (the message key is the transactionId). So:
 * <ul>
 *   <li>A transport failure (Kafka down, timeout) stops the pass. No attempt is counted.</li>
 *   <li>A row failure (stored JSON unreadable, record rejected) counts one attempt and blocks only
 *       later rows for the same transaction in this pass. Other transactions still go out.</li>
 *   <li>After {@code transaction.outbox.max-attempts} row failures the row is marked failed, logged
 *       with its transactionId, and counted in {@code transaction.outbox.failed}.</li>
 * </ul>
 * Two replicas may publish the same row; consumers deduplicate on eventId.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final OutboxPublisher outboxPublisher;
    private final Counter failedRows;
    private final int maxAttempts;
    private final boolean scheduledRelayEnabled;

    public OutboxRelay(OutboxRepository outboxRepository,
                       OutboxPublisher outboxPublisher,
                       MeterRegistry meterRegistry,
                       @Value("${transaction.outbox.max-attempts:5}") int maxAttempts,
                       @Value("${transaction.outbox.relay-enabled:true}") boolean scheduledRelayEnabled) {
        this.outboxRepository = outboxRepository;
        this.outboxPublisher = outboxPublisher;
        this.failedRows = Counter.builder("transaction.outbox.failed")
                .description("Outbox rows marked failed after the attempt limit")
                .register(meterRegistry);
        this.maxAttempts = maxAttempts;
        this.scheduledRelayEnabled = scheduledRelayEnabled;
    }

    /** Scheduled pass. Tests turn it off and call {@link #relayBatch()} themselves. */
    @Scheduled(fixedDelayString = "${transaction.outbox.poll-interval-ms:500}")
    public void relayPending() {
        if (scheduledRelayEnabled) {
            relayBatch();
        }
    }

    /** Returns the number of rows published in this pass. */
    public int relayBatch() {
        List<OutboxEvent> batch = outboxRepository.findTop100BySentAtIsNullAndFailedAtIsNullOrderByCreatedAtAsc();
        Set<String> blockedTransactions = new HashSet<>();
        int sent = 0;
        for (OutboxEvent row : batch) {
            if (blockedTransactions.contains(row.getMessageKey())) {
                continue;
            }
            try {
                outboxPublisher.publish(row);
                sent++;
            } catch (OutboxRowException e) {
                blockedTransactions.add(row.getMessageKey());
                recordRowFailure(row, e);
            } catch (RuntimeException e) {
                log.warn("Outbox relay stopped by a transport failure at event {}: {}",
                        row.getEventId(), e.getMessage());
                break;
            }
        }
        return sent;
    }

    private void recordRowFailure(OutboxEvent row, RuntimeException e) {
        if (outboxPublisher.recordFailure(row, e.getMessage(), maxAttempts)) {
            failedRows.increment();
            log.error("Outbox row {} ({}) for transactionId {} marked failed after {} attempts: {}",
                    row.getEventId(), row.getEventType(), row.getMessageKey(), maxAttempts, e.getMessage());
        } else {
            log.warn("Outbox row {} for transactionId {} failed, will retry: {}",
                    row.getEventId(), row.getMessageKey(), e.getMessage());
        }
    }
}
