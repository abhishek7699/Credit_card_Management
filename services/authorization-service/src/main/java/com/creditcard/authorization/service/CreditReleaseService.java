package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.TimeoutMarker;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles {@code transaction.timed_out} (013). One DB transaction: the duplicate check, the card row lock,
 * and the state change commit together, so a redelivered event changes nothing.
 *
 * <ul>
 *   <li>An AUTHORIZED deduction exists: its amount goes back to the card, and the record becomes RELEASED.</li>
 *   <li>No record exists yet: a timeout marker is stored, so a late transaction.initiated is skipped.</li>
 *   <li>Any other outcome (DECLINED, FAILED, already RELEASED): nothing to give back.</li>
 * </ul>
 */
@Service
public class CreditReleaseService {

    private static final Logger log = LoggerFactory.getLogger(CreditReleaseService.class);

    private final IdempotencyService idempotencyService;
    private final CardAccountRepository cardAccountRepository;
    private final AuthorizationRecordRepository authorizationRecordRepository;
    private final TimeoutMarkerRepository timeoutMarkerRepository;

    public CreditReleaseService(IdempotencyService idempotencyService,
                                CardAccountRepository cardAccountRepository,
                                AuthorizationRecordRepository authorizationRecordRepository,
                                TimeoutMarkerRepository timeoutMarkerRepository) {
        this.idempotencyService = idempotencyService;
        this.cardAccountRepository = cardAccountRepository;
        this.authorizationRecordRepository = authorizationRecordRepository;
        this.timeoutMarkerRepository = timeoutMarkerRepository;
    }

    @Transactional
    public void release(String eventId, UUID transactionId, String cardToken) {
        if (idempotencyService.isDuplicate(idempotencyService.buildKey("TransactionTimedOut", eventId))) {
            log.info("Skipping duplicate transaction.timed_out eventId={} txId={}", eventId, transactionId);
            return;
        }

        Optional<CardAccount> account = cardAccountRepository.findByCardTokenForUpdate(cardToken);
        List<AuthorizationRecord> records = authorizationRecordRepository.findByTransactionId(transactionId);

        if (records.isEmpty()) {
            if (!timeoutMarkerRepository.existsById(transactionId)) {
                timeoutMarkerRepository.save(new TimeoutMarker(transactionId));
            }
            log.info("Timeout marker set for txId={}: no authorization had run", transactionId);
            return;
        }

        for (AuthorizationRecord record : records) {
            if (record.getOutcome() != AuthorizationOutcome.AUTHORIZED) {
                continue;
            }
            CardAccount held = account.orElseThrow(() -> new IllegalStateException(
                    "no card account for the authorized deduction of txId " + transactionId));
            held.setAvailableCredit(held.getAvailableCredit().add(record.getRequestedAmount()));
            cardAccountRepository.save(held);
            record.setOutcome(AuthorizationOutcome.RELEASED);
            authorizationRecordRepository.save(record);
            log.info("Released {} {} held for timed-out txId={}",
                    record.getRequestedAmount(), record.getCurrencyCode(), transactionId);
        }
    }
}
