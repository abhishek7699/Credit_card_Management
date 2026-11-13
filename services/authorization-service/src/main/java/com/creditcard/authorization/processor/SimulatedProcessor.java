package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The simulator processor: decides from {@code card_accounts}. It is the default (spec 014 phase 1).
 *
 * <p>It does its own card lookup and locking. Each call takes the card row lock first, then checks the
 * timeout marker (013), then decides. That order is what keeps a timeout release and an authorization for
 * the same card from interleaving.
 *
 * <p>Not a Spring bean. {@code ProcessorConfig} creates it.
 */
public class SimulatedProcessor implements PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(SimulatedProcessor.class);

    private final CardAccountRepository cardAccountRepository;
    private final AuthorizationRecordRepository authorizationRecordRepository;
    private final TimeoutMarkerRepository timeoutMarkerRepository;

    public SimulatedProcessor(CardAccountRepository cardAccountRepository,
                              AuthorizationRecordRepository authorizationRecordRepository,
                              TimeoutMarkerRepository timeoutMarkerRepository) {
        this.cardAccountRepository = cardAccountRepository;
        this.authorizationRecordRepository = authorizationRecordRepository;
        this.timeoutMarkerRepository = timeoutMarkerRepository;
    }

    /** Same order as before: lock the card, check the marker, then missing card, inactive, insufficient, approve. */
    @Override
    public ProcessorDecision authorize(AuthorizeRequest request) {
        Optional<CardAccount> accountOpt = cardAccountRepository.findByCardTokenForUpdate(request.getPaymentToken());

        if (timeoutMarkerRepository.existsById(request.getTransactionId())) {
            return ProcessorDecision.timedOutSkipped();
        }
        if (accountOpt.isEmpty()) {
            return ProcessorDecision.declined(ReasonCode.INVALID_CARD.name());
        }
        CardAccount account = accountOpt.get();
        if (!account.getStatus().isActive()) {
            return ProcessorDecision.declined(ReasonCode.CARD_NOT_ACTIVE.name());
        }
        if (account.getAvailableCredit().compareTo(request.getAmount()) < 0) {
            return ProcessorDecision.declined(ReasonCode.INSUFFICIENT_CREDIT.name());
        }

        account.setAvailableCredit(account.getAvailableCredit().subtract(request.getAmount()));
        cardAccountRepository.save(account);
        return ProcessorDecision.approved(null);
    }

    /** The simulator has no remote state to look up. Always UNKNOWN. */
    @Override
    public ProcessorDecision lookup(UUID transactionId) {
        return ProcessorDecision.unknown();
    }

    /** The simulator never captures. Always CAPTURE_REJECTED. */
    @Override
    public ProcessorDecision capture(UUID transactionId, BigDecimal amount, String currencyCode) {
        return ProcessorDecision.captureRejected("SIMULATOR_NO_CAPTURE");
    }

    /**
     * Give the authorized amount back once (013). The card row is locked first, then the records are read.
     * Each AUTHORIZED record is restored to {@code available_credit} and set to RELEASED.
     */
    @Override
    public ProcessorDecision releaseHold(UUID transactionId, String paymentToken) {
        Optional<CardAccount> accountOpt = cardAccountRepository.findByCardTokenForUpdate(paymentToken);
        List<AuthorizationRecord> records = authorizationRecordRepository.findByTransactionId(transactionId);
        if (records.isEmpty()) {
            return ProcessorDecision.noAuthorization();
        }

        boolean released = false;
        for (AuthorizationRecord record : records) {
            if (record.getOutcome() != AuthorizationOutcome.AUTHORIZED) {
                continue;
            }
            CardAccount held = accountOpt.orElseThrow(() -> new IllegalStateException(
                    "no card account for the authorized deduction of txId " + transactionId));
            held.setAvailableCredit(held.getAvailableCredit().add(record.getRequestedAmount()));
            cardAccountRepository.save(held);
            record.setOutcome(AuthorizationOutcome.RELEASED);
            authorizationRecordRepository.save(record);
            released = true;
            log.info("Released {} {} held for timed-out txId={}",
                    record.getRequestedAmount(), record.getCurrencyCode(), transactionId);
        }
        return released ? ProcessorDecision.released() : ProcessorDecision.noOp();
    }
}
