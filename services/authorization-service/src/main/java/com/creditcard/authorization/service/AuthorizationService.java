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
        Optional<CardAccount> accountOpt = cardAccountRepository.findByCardToken(event.getCardToken());

        if (accountOpt.isEmpty()) {
            return persistAndReturn(event, AuthorizationOutcome.DECLINED, ReasonCode.INVALID_CARD);
        }

        CardAccount account = accountOpt.get();

        if (!account.getStatus().isActive()) {
            return persistAndReturn(event, AuthorizationOutcome.DECLINED, ReasonCode.CARD_NOT_ACTIVE);
        }

        if (account.getAvailableCredit().compareTo(event.getAmount()) < 0) {
            return persistAndReturn(event, AuthorizationOutcome.DECLINED, ReasonCode.INSUFFICIENT_CREDIT);
        }

        account.setAvailableCredit(account.getAvailableCredit().subtract(event.getAmount()));
        cardAccountRepository.save(account);

        return persistAndReturn(event, AuthorizationOutcome.AUTHORIZED, null);
    }

    AuthorizationResultEvent persistAndReturn(
            TransactionInitiatedEvent event,
            AuthorizationOutcome outcome,
            ReasonCode reasonCode) {

        AuthorizationRecord record = new AuthorizationRecord();
        record.setTransactionId(event.getTransactionId());
        record.setCardToken(event.getCardToken());
        record.setRequestedAmount(event.getAmount());
        record.setCurrencyCode(event.getCurrencyCode());
        record.setOutcome(outcome);
        record.setReasonCode(reasonCode);
        record.setDecidedAt(Instant.now());
        authorizationRecordRepository.save(record);

        return switch (outcome) {
            case AUTHORIZED -> AuthorizationResultEvent.authorized(event.getTransactionId(), event.getCardToken());
            case DECLINED -> AuthorizationResultEvent.declined(event.getTransactionId(), event.getCardToken(), reasonCode);
            case FAILED -> AuthorizationResultEvent.failed(event.getTransactionId(), event.getCardToken(), reasonCode);
        };
    }
}
