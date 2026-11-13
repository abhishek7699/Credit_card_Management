package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.processor.AuthorizeRequest;
import com.creditcard.authorization.processor.PaymentProcessor;
import com.creditcard.authorization.processor.ProcessorDecision;
import com.creditcard.authorization.processor.ProcessorOutcome;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class AuthorizationService {

    private final PaymentProcessor paymentProcessor;
    private final CardAccountRepository cardAccountRepository;
    private final AuthorizationRecordRepository authorizationRecordRepository;
    private final TimeoutMarkerRepository timeoutMarkerRepository;

    public AuthorizationService(
            PaymentProcessor paymentProcessor,
            CardAccountRepository cardAccountRepository,
            AuthorizationRecordRepository authorizationRecordRepository,
            TimeoutMarkerRepository timeoutMarkerRepository) {
        this.paymentProcessor = paymentProcessor;
        this.cardAccountRepository = cardAccountRepository;
        this.authorizationRecordRepository = authorizationRecordRepository;
        this.timeoutMarkerRepository = timeoutMarkerRepository;
    }

    /**
     * Decides one transaction. Returns empty when the transaction already timed out (013): no credit is
     * held and no result is published, because transaction-service has already recorded FAILED.
     *
     * <p>The card row is locked before the timeout marker is checked. The timeout release takes the same
     * lock, so the two cannot interleave: either the release runs first and this call sees its marker, or
     * this call runs first and the release sees its deduction.
     *
     * <p>Spec 014 phase 1: the decision is made by {@link PaymentProcessor}. This service keeps the lock,
     * the marker check and the authorization record, for every processor.
     */
    @Transactional
    @Retryable(
        retryFor = {
            org.springframework.dao.OptimisticLockingFailureException.class,
            org.springframework.dao.CannotSerializeTransactionException.class
        },
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 500)
    )
    public Optional<AuthorizationResultEvent> authorize(TransactionInitiatedEvent event) {
        TransactionInitiatedEvent.Payload payload = event.getPayload();
        Optional<CardAccount> accountOpt = cardAccountRepository.findByCardTokenForUpdate(payload.getCardToken());

        if (timeoutMarkerRepository.existsById(payload.getTransactionId())) {
            return Optional.empty();
        }

        AuthorizeRequest request = new AuthorizeRequest(
                payload.getTransactionId(), payload.getAmount(), payload.getCurrencyCode(),
                payload.getMerchantId(), accountOpt.orElse(null));
        ProcessorDecision decision = paymentProcessor.authorize(request);

        if (decision.getOutcome() == ProcessorOutcome.APPROVED) {
            return Optional.of(persistAndReturn(payload, AuthorizationOutcome.AUTHORIZED, null));
        }
        return Optional.of(persistAndReturn(payload, AuthorizationOutcome.DECLINED,
                ReasonCode.valueOf(decision.getReasonCode())));
    }

    AuthorizationResultEvent persistAndReturn(
            TransactionInitiatedEvent.Payload payload,
            AuthorizationOutcome outcome,
            ReasonCode reasonCode) {

        if (outcome == AuthorizationOutcome.RELEASED) {
            throw new IllegalStateException(
                    "RELEASED is set only by the timeout release, never by an authorization decision");
        }

        AuthorizationRecord record = new AuthorizationRecord();
        record.setTransactionId(payload.getTransactionId());
        record.setCardToken(payload.getCardToken());
        record.setRequestedAmount(payload.getAmount());
        record.setCurrencyCode(payload.getCurrencyCode());
        record.setOutcome(outcome);
        record.setReasonCode(reasonCode);
        record.setDecidedAt(Instant.now());
        authorizationRecordRepository.save(record);

        return switch (outcome) {
            case AUTHORIZED -> AuthorizationResultEvent.authorized(payload.getTransactionId(), payload.getCardToken());
            case DECLINED -> AuthorizationResultEvent.declined(payload.getTransactionId(), payload.getCardToken(), reasonCode);
            case FAILED, RELEASED -> AuthorizationResultEvent.failed(payload.getTransactionId(), payload.getCardToken(), reasonCode);
        };
    }
}
