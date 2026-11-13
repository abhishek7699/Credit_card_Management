package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The simulator processor: decides from {@code card_accounts}, which is the default (spec 014 phase 1).
 * Its behavior is the same as the code it replaces in {@code AuthorizationService} and
 * {@code CreditReleaseService} (spec 013).
 *
 * <p>Not a Spring bean. {@code ProcessorConfig} creates it, so there is exactly one processor bean.
 */
public class SimulatedProcessor implements PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(SimulatedProcessor.class);

    private final CardAccountRepository cardAccountRepository;
    private final AuthorizationRecordRepository authorizationRecordRepository;

    public SimulatedProcessor(CardAccountRepository cardAccountRepository,
                              AuthorizationRecordRepository authorizationRecordRepository) {
        this.cardAccountRepository = cardAccountRepository;
        this.authorizationRecordRepository = authorizationRecordRepository;
    }

    /** Same decision order as before: missing card, inactive card, insufficient credit, then approve. */
    @Override
    public ProcessorDecision authorize(AuthorizeRequest request) {
        CardAccount account = request.getLockedAccount();

        if (account == null) {
            return ProcessorDecision.declined(ReasonCode.INVALID_CARD.name());
        }
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

    /** The simulator has no remote state to look up. Phase 1: always UNKNOWN. */
    @Override
    public ProcessorDecision lookup(UUID transactionId) {
        return ProcessorDecision.unknown();
    }

    /** The simulator never captures. Phase 1: always CAPTURE_REJECTED. */
    @Override
    public ProcessorDecision capture(UUID transactionId, BigDecimal amount, String currencyCode) {
        return ProcessorDecision.captureRejected("SIMULATOR_NO_CAPTURE");
    }

    /**
     * Give the authorized amount back once (013). Moved from {@code CreditReleaseService}: each AUTHORIZED
     * record is restored to {@code available_credit} and set to RELEASED. The caller holds the card lock.
     */
    @Override
    public ProcessorDecision releaseHold(UUID transactionId, CardAccount lockedAccount) {
        List<AuthorizationRecord> records = authorizationRecordRepository.findByTransactionId(transactionId);
        if (records.isEmpty()) {
            return ProcessorDecision.noAuthorization();
        }

        boolean released = false;
        for (AuthorizationRecord record : records) {
            if (record.getOutcome() != AuthorizationOutcome.AUTHORIZED) {
                continue;
            }
            if (lockedAccount == null) {
                throw new IllegalStateException(
                        "no card account for the authorized deduction of txId " + transactionId);
            }
            lockedAccount.setAvailableCredit(lockedAccount.getAvailableCredit().add(record.getRequestedAmount()));
            cardAccountRepository.save(lockedAccount);
            record.setOutcome(AuthorizationOutcome.RELEASED);
            authorizationRecordRepository.save(record);
            released = true;
            log.info("Released {} {} held for timed-out txId={}",
                    record.getRequestedAmount(), record.getCurrencyCode(), transactionId);
        }
        return released ? ProcessorDecision.released() : ProcessorDecision.noOp();
    }
}
