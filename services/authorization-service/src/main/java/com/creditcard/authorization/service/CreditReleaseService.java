package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.TimeoutMarker;
import com.creditcard.authorization.processor.PaymentProcessor;
import com.creditcard.authorization.processor.ProcessorDecision;
import com.creditcard.authorization.processor.ProcessorOutcome;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles {@code transaction.timed_out} (013). One DB transaction: the duplicate check, the processor's
 * release (which serializes with authorization), and the marker write commit together.
 *
 * <p>The service keeps the duplicate check and the timeout marker. A marker must block a late
 * authorization for every processor. The give-back itself is {@link PaymentProcessor#releaseHold}.
 */
@Service
public class CreditReleaseService {

    private static final Logger log = LoggerFactory.getLogger(CreditReleaseService.class);

    private final IdempotencyService idempotencyService;
    private final TimeoutMarkerRepository timeoutMarkerRepository;
    private final PaymentProcessor paymentProcessor;

    public CreditReleaseService(IdempotencyService idempotencyService,
                                TimeoutMarkerRepository timeoutMarkerRepository,
                                PaymentProcessor paymentProcessor) {
        this.idempotencyService = idempotencyService;
        this.timeoutMarkerRepository = timeoutMarkerRepository;
        this.paymentProcessor = paymentProcessor;
    }

    @Transactional
    public void release(String eventId, UUID transactionId, String paymentToken) {
        if (idempotencyService.isDuplicate(idempotencyService.buildKey("TransactionTimedOut", eventId))) {
            log.info("Skipping duplicate transaction.timed_out eventId={} txId={}", eventId, transactionId);
            return;
        }

        ProcessorDecision decision = paymentProcessor.releaseHold(transactionId, paymentToken);

        if (decision.getOutcome() == ProcessorOutcome.NO_AUTHORIZATION) {
            if (!timeoutMarkerRepository.existsById(transactionId)) {
                timeoutMarkerRepository.save(new TimeoutMarker(transactionId));
            }
            log.info("Timeout marker set for txId={}: no authorization had run", transactionId);
        }
    }
}
