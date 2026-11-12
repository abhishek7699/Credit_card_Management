package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.OutboxEvent;
import com.creditcard.transaction.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polling relay for the transactional outbox (013). Publishes unsent rows oldest first and
 * stops at the first failure, so a later event for a transaction is never sent ahead of an
 * earlier one that failed. Two replicas may publish the same row; consumers deduplicate.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final OutboxPublisher outboxPublisher;
    private final boolean scheduledRelayEnabled;

    public OutboxRelay(OutboxRepository outboxRepository,
                       OutboxPublisher outboxPublisher,
                       @Value("${transaction.outbox.relay-enabled:true}") boolean scheduledRelayEnabled) {
        this.outboxRepository = outboxRepository;
        this.outboxPublisher = outboxPublisher;
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
        List<OutboxEvent> batch = outboxRepository.findTop100BySentAtIsNullOrderByCreatedAtAsc();
        int sent = 0;
        for (OutboxEvent row : batch) {
            try {
                outboxPublisher.publish(row);
                sent++;
            } catch (RuntimeException e) {
                log.warn("Outbox relay stopped at event {}: {}", row.getEventId(), e.getMessage());
                break;
            }
        }
        return sent;
    }
}
