package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
import com.creditcard.authorization.domain.TimeoutMarker;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins today's timeout-release behavior (spec 014 phase 1, T003). Written against the unchanged
 * {@link CreditReleaseService} and kept unchanged through the refactor. Only {@link #service()} changes.
 */
@ExtendWith(MockitoExtension.class)
class CreditReleaseCharacterizationTest {

    private static final String TOKEN = "vault:v1:tok";
    private static final String EVENT_ID = "evt-1";

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private CardAccountRepository cardAccountRepository;

    @Mock
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Mock
    private TimeoutMarkerRepository timeoutMarkerRepository;

    private CreditReleaseService service() {
        return new CreditReleaseService(idempotencyService, cardAccountRepository, timeoutMarkerRepository,
                new com.creditcard.authorization.processor.SimulatedProcessor(
                        cardAccountRepository, authorizationRecordRepository));
    }

    @Test
    void releaseRestoresTheHeldAmountOnce() {
        UUID txId = UUID.randomUUID();
        CardAccount account = account("400.00");
        AuthorizationRecord record = record(txId, AuthorizationOutcome.AUTHORIZED, "100.00");
        notDuplicate();
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account));
        when(authorizationRecordRepository.findByTransactionId(txId)).thenReturn(List.of(record));

        service().release(EVENT_ID, txId, TOKEN);

        assertThat(account.getAvailableCredit()).isEqualByComparingTo("500.00");
        assertThat(record.getOutcome()).isEqualTo(AuthorizationOutcome.RELEASED);
        verify(cardAccountRepository).save(account);
        verify(authorizationRecordRepository).save(record);
    }

    @Test
    void aRedeliveredEventChangesNothing() {
        UUID txId = UUID.randomUUID();
        when(idempotencyService.buildKey("TransactionTimedOut", EVENT_ID)).thenReturn("key");
        when(idempotencyService.isDuplicate("key")).thenReturn(true);

        service().release(EVENT_ID, txId, TOKEN);

        verify(cardAccountRepository, never()).findByCardTokenForUpdate(any());
        verify(timeoutMarkerRepository, never()).save(any());
    }

    @Test
    void releaseWithNoRecordWritesATimeoutMarker() {
        UUID txId = UUID.randomUUID();
        notDuplicate();
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account("400.00")));
        when(authorizationRecordRepository.findByTransactionId(txId)).thenReturn(List.of());
        when(timeoutMarkerRepository.existsById(txId)).thenReturn(false);

        service().release(EVENT_ID, txId, TOKEN);

        verify(timeoutMarkerRepository).save(any(TimeoutMarker.class));
        verify(cardAccountRepository, never()).save(any());
    }

    @Test
    void releaseOfADeclinedRecordChangesNothing() {
        UUID txId = UUID.randomUUID();
        CardAccount account = account("400.00");
        notDuplicate();
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account));
        when(authorizationRecordRepository.findByTransactionId(txId))
                .thenReturn(List.of(record(txId, AuthorizationOutcome.DECLINED, "100.00")));

        service().release(EVENT_ID, txId, TOKEN);

        assertThat(account.getAvailableCredit()).isEqualByComparingTo("400.00");
        verify(cardAccountRepository, never()).save(any());
    }

    private void notDuplicate() {
        when(idempotencyService.buildKey("TransactionTimedOut", EVENT_ID)).thenReturn("key");
        when(idempotencyService.isDuplicate("key")).thenReturn(false);
    }

    private static CardAccount account(String availableCredit) {
        CardAccount account = new CardAccount();
        account.setCardToken(TOKEN);
        account.setStatus(CardAccountStatus.ACTIVE);
        account.setCreditLimit(new BigDecimal("1000.00"));
        account.setAvailableCredit(new BigDecimal(availableCredit));
        account.setCurrencyCode("USD");
        return account;
    }

    private static AuthorizationRecord record(UUID txId, AuthorizationOutcome outcome, String amount) {
        AuthorizationRecord record = new AuthorizationRecord();
        record.setTransactionId(txId);
        record.setCardToken(TOKEN);
        record.setRequestedAmount(new BigDecimal(amount));
        record.setCurrencyCode("USD");
        record.setOutcome(outcome);
        return record;
    }
}
