package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.processor.AuthorizeRequest;
import com.creditcard.authorization.processor.PaymentProcessor;
import com.creditcard.authorization.processor.ProcessorDecision;
import com.creditcard.authorization.processor.ProcessorOutcome;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class AuthorizationService {

    private final PaymentProcessor paymentProcessor;
    private final AuthorizationRecordRepository authorizationRecordRepository;

    public AuthorizationService(
            PaymentProcessor paymentProcessor,
            AuthorizationRecordRepository authorizationRecordRepository) {
        this.paymentProcessor = paymentProcessor;
        this.authorizationRecordRepository = authorizationRecordRepository;
    }

    /**
     * Decides one transaction. Returns empty when no result should be published:
     * <ul>
     *   <li>the transaction already timed out (013). transaction-service has recorded FAILED.</li>
     *   <li>the processor could not decide (UNKNOWN). The transaction stays PENDING, and the 013 sweep
     *       fails it later.</li>
     * </ul>
     *
     * <p>The processor serializes this with the timeout release, and checks the timeout marker itself
     * (see {@link PaymentProcessor}). This service writes the authorization record for decisions only.
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
        AuthorizeRequest request = new AuthorizeRequest(
                payload.getTransactionId(), payload.getAmount(), payload.getCurrencyCode(),
                payload.getMerchantId(), payload.getCardToken());
        ProcessorDecision decision = paymentProcessor.authorize(request);

        if (decision.getOutcome() == ProcessorOutcome.APPROVED) {
            return Optional.of(persistAndReturn(payload, AuthorizationOutcome.AUTHORIZED, null));
        }
        if (decision.getOutcome() == ProcessorOutcome.DECLINED) {
            return Optional.of(persistAndReturn(payload, AuthorizationOutcome.DECLINED,
                    ReasonCode.valueOf(decision.getReasonCode()), decision.getProcessorCode()));
        }
        return Optional.empty();
    }

    AuthorizationResultEvent persistAndReturn(
            TransactionInitiatedEvent.Payload payload,
            AuthorizationOutcome outcome,
            ReasonCode reasonCode) {
        return persistAndReturn(payload, outcome, reasonCode, null);
    }

    AuthorizationResultEvent persistAndReturn(
            TransactionInitiatedEvent.Payload payload,
            AuthorizationOutcome outcome,
            ReasonCode reasonCode,
            String processorCode) {

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
        record.setProcessorReasonCode(processorCode);
        record.setDecidedAt(Instant.now());
        authorizationRecordRepository.save(record);

        return switch (outcome) {
            case AUTHORIZED -> AuthorizationResultEvent.authorized(payload.getTransactionId(), payload.getCardToken());
            case DECLINED -> AuthorizationResultEvent.declined(payload.getTransactionId(), payload.getCardToken(), reasonCode);
            case FAILED, RELEASED -> AuthorizationResultEvent.failed(payload.getTransactionId(), payload.getCardToken(), reasonCode);
        };
    }
}
