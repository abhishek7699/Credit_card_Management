package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.TimeoutMarker;
import com.creditcard.authorization.processor.PaymentProcessor;
import com.creditcard.authorization.processor.ProcessorDecision;
import com.creditcard.authorization.processor.ProcessorOutcome;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles {@code transaction.timed_out} (013). One DB transaction: the duplicate check, the card row lock,
 * and the state change commit together, so a redelivered event changes nothing.
 *
 * <p>Spec 014 phase 1: the give-back is done by {@link PaymentProcessor#releaseHold}. This service keeps the
 * duplicate check and the timeout marker, because a marker must block a late authorization for every
 * processor.
 *
 * <ul>
 *   <li>The processor released an AUTHORIZED deduction: its amount goes back to the card.</li>
 *   <li>The processor reports no authorization record exists: a timeout marker is stored, so a late
 *       transaction.initiated is skipped.</li>
 *   <li>Any other outcome (DECLINED, FAILED, already RELEASED): nothing to give back.</li>
 * </ul>
 */
@Service
public class CreditReleaseService {

    private static final Logger log = LoggerFactory.getLogger(CreditReleaseService.class);

    private final IdempotencyService idempotencyService;
    private final CardAccountRepository cardAccountRepository;
    private final TimeoutMarkerRepository timeoutMarkerRepository;
    private final PaymentProcessor paymentProcessor;

    public CreditReleaseService(IdempotencyService idempotencyService,
                                CardAccountRepository cardAccountRepository,
                                TimeoutMarkerRepository timeoutMarkerRepository,
                                PaymentProcessor paymentProcessor) {
        this.idempotencyService = idempotencyService;
        this.cardAccountRepository = cardAccountRepository;
        this.timeoutMarkerRepository = timeoutMarkerRepository;
        this.paymentProcessor = paymentProcessor;
    }

    @Transactional
    public void release(String eventId, UUID transactionId, String cardToken) {
        if (idempotencyService.isDuplicate(idempotencyService.buildKey("TransactionTimedOut", eventId))) {
            log.info("Skipping duplicate transaction.timed_out eventId={} txId={}", eventId, transactionId);
            return;
        }

        Optional<CardAccount> account = cardAccountRepository.findByCardTokenForUpdate(cardToken);
        ProcessorDecision decision = paymentProcessor.releaseHold(transactionId, account.orElse(null));

        if (decision.getOutcome() == ProcessorOutcome.NO_AUTHORIZATION) {
            if (!timeoutMarkerRepository.existsById(transactionId)) {
                timeoutMarkerRepository.save(new TimeoutMarker(transactionId));
            }
            log.info("Timeout marker set for txId={}: no authorization had run", transactionId);
        }
    }
}
