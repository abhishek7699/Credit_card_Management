package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class AuthorizationService {

    private final CardAccountRepository cardAccountRepository;
    private final AuthorizationRecordRepository authorizationRecordRepository;

    public AuthorizationService(
            CardAccountRepository cardAccountRepository,
            AuthorizationRecordRepository authorizationRecordRepository) {
        this.cardAccountRepository = cardAccountRepository;
        this.authorizationRecordRepository = authorizationRecordRepository;
    }

    @Transactional
    @Retryable(
        retryFor = {
            org.springframework.dao.OptimisticLockingFailureException.class,
            org.springframework.dao.CannotSerializeTransactionException.class
        },
        maxAttempts = 3,
        backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 500)
    )
    public AuthorizationResultEvent authorize(TransactionInitiatedEvent event) {
        TransactionInitiatedEvent.Payload payload = event.getPayload();
        Optional<CardAccount> accountOpt = cardAccountRepository.findByCardToken(payload.getCardToken());

        if (accountOpt.isEmpty()) {
            return persistAndReturn(payload, AuthorizationOutcome.DECLINED, ReasonCode.INVALID_CARD);
        }

        CardAccount account = accountOpt.get();

        if (!account.getStatus().isActive()) {
            return persistAndReturn(payload, AuthorizationOutcome.DECLINED, ReasonCode.CARD_NOT_ACTIVE);
        }

        if (account.getAvailableCredit().compareTo(payload.getAmount()) < 0) {
            return persistAndReturn(payload, AuthorizationOutcome.DECLINED, ReasonCode.INSUFFICIENT_CREDIT);
        }

        account.setAvailableCredit(account.getAvailableCredit().subtract(payload.getAmount()));
        cardAccountRepository.save(account);

        return persistAndReturn(payload, AuthorizationOutcome.AUTHORIZED, null);
    }

    AuthorizationResultEvent persistAndReturn(
            TransactionInitiatedEvent.Payload payload,
            AuthorizationOutcome outcome,
            ReasonCode reasonCode) {

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
            case FAILED -> AuthorizationResultEvent.failed(payload.getTransactionId(), payload.getCardToken(), reasonCode);
        };
    }
}
