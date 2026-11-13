package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PENDING sweep (013). Every interval it times out transactions that have been PENDING longer than P.
 * Each one is handled in its own DB transaction through {@link TransactionService#timeOutPending}, and
 * the conditional update means two replicas never time out the same row.
 *
 * <p>A row that fails for its own reason is logged and skipped, so it cannot block the rows behind it.
 * It is retried on the next pass.
 */
@Component
public class TransactionTimeoutSweep {

    private static final Logger log = LoggerFactory.getLogger(TransactionTimeoutSweep.class);
    static final int BATCH_SIZE = 100;

    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final Duration pendingTimeout;
    private final boolean scheduledEnabled;

    public TransactionTimeoutSweep(TransactionRepository transactionRepository,
                                   TransactionService transactionService,
                                   @Value("${transaction.timeouts.pending-timeout-seconds:300}") long pendingTimeoutSeconds,
                                   @Value("${transaction.timeouts.sweep-enabled:true}") boolean scheduledEnabled) {
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.pendingTimeout = Duration.ofSeconds(pendingTimeoutSeconds);
        this.scheduledEnabled = scheduledEnabled;
    }

    /** Scheduled pass. Tests turn it off and call {@link #sweepOnce()} themselves. */
    @Scheduled(fixedDelayString = "${transaction.timeouts.sweep-interval-ms:30000}")
    public void sweepScheduled() {
        if (scheduledEnabled) {
            sweepOnce();
        }
    }

    /** Returns how many transactions this pass timed out. */
    public int sweepOnce() {
        Instant now = Instant.now();
        List<UUID> ids = transactionRepository.findIdsByStatusCreatedBefore(
                TransactionStatus.PENDING, now.minus(pendingTimeout), PageRequest.of(0, BATCH_SIZE));
        int timedOut = 0;
        for (UUID id : ids) {
            try {
                if (transactionService.timeOutPending(id, now)) {
                    timedOut++;
                }
            } catch (RuntimeException e) {
                log.error("PENDING timeout failed for transactionId {}; will retry on the next pass: {}",
                        id, e.getMessage());
            }
        }
        return timedOut;
    }
}
