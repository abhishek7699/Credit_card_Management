package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.TimeoutMarker;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditReleaseServiceTest {

    private static final String KEY = "auth:TransactionTimedOut:evt-1";

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private CardAccountRepository cardAccountRepository;

    @Mock
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Mock
    private TimeoutMarkerRepository timeoutMarkerRepository;

    private CreditReleaseService service() {
        return new CreditReleaseService(idempotencyService, timeoutMarkerRepository,
                new com.creditcard.authorization.processor.SimulatedProcessor(
                        cardAccountRepository, authorizationRecordRepository, timeoutMarkerRepository));
    }

    /** Not a duplicate: the release proceeds. */
    private void notDuplicate() {
        when(idempotencyService.buildKey("TransactionTimedOut", "evt-1")).thenReturn(KEY);
        when(idempotencyService.isDuplicate(KEY)).thenReturn(false);
    }

    private static AuthorizationRecord record(AuthorizationOutcome outcome) {
        AuthorizationRecord record = new AuthorizationRecord();
        record.setOutcome(outcome);
        record.setRequestedAmount(new BigDecimal("100.00"));
        record.setCurrencyCode("USD");
        return record;
    }

    private static CardAccount account(String available) {
        CardAccount account = new CardAccount();
        account.setAvailableCredit(new BigDecimal(available));
        return account;
    }

    @Test
    void aDuplicateEventChangesNothing() {
        when(idempotencyService.buildKey("TransactionTimedOut", "evt-1")).thenReturn(KEY);
        when(idempotencyService.isDuplicate(KEY)).thenReturn(true);

        service().release("evt-1", UUID.randomUUID(), "vault:v1:tok");

        verifyNoInteractions(cardAccountRepository, authorizationRecordRepository, timeoutMarkerRepository);
    }

    @Test
    void restoresTheHeldAmountOnceAndMarksTheDeductionReleased() {
        notDuplicate();
        UUID txId = UUID.randomUUID();
        CardAccount card = account("400.00");
        AuthorizationRecord deduction = record(AuthorizationOutcome.AUTHORIZED);
        when(cardAccountRepository.findByCardTokenForUpdate("vault:v1:tok")).thenReturn(Optional.of(card));
        when(authorizationRecordRepository.findByTransactionId(txId)).thenReturn(List.of(deduction));

        service().release("evt-1", txId, "vault:v1:tok");

        assertThat(card.getAvailableCredit()).isEqualByComparingTo("500.00");
        verify(cardAccountRepository).save(card);
        assertThat(deduction.getOutcome()).isEqualTo(AuthorizationOutcome.RELEASED);
        verify(authorizationRecordRepository).save(deduction);
        verify(timeoutMarkerRepository, never()).save(any());
    }

    @Test
    void declinedAndAlreadyReleasedRecordsGiveNothingBack() {
        notDuplicate();
        UUID txId = UUID.randomUUID();
        CardAccount card = account("400.00");
        when(cardAccountRepository.findByCardTokenForUpdate("vault:v1:tok")).thenReturn(Optional.of(card));
        when(authorizationRecordRepository.findByTransactionId(txId)).thenReturn(List.of(
                record(AuthorizationOutcome.DECLINED), record(AuthorizationOutcome.RELEASED)));

        service().release("evt-1", txId, "vault:v1:tok");

        assertThat(card.getAvailableCredit()).isEqualByComparingTo("400.00");
        verify(cardAccountRepository, never()).save(any());
        verify(authorizationRecordRepository, never()).save(any());
    }

    @Test
    void whenNoAuthorizationRanItStoresATimeoutMarker() {
        notDuplicate();
        UUID txId = UUID.randomUUID();
        when(authorizationRecordRepository.findByTransactionId(txId)).thenReturn(List.of());
        when(timeoutMarkerRepository.existsById(txId)).thenReturn(false);

        service().release("evt-1", txId, "vault:v1:tok");

        ArgumentCaptor<TimeoutMarker> marker = ArgumentCaptor.forClass(TimeoutMarker.class);
        verify(timeoutMarkerRepository).save(marker.capture());
        assertThat(marker.getValue().getTransactionId()).isEqualTo(txId);
        verify(cardAccountRepository, never()).save(any());
    }

    @Test
    void anExistingMarkerIsNotStoredTwice() {
        notDuplicate();
        UUID txId = UUID.randomUUID();
        when(authorizationRecordRepository.findByTransactionId(txId)).thenReturn(List.of());
        when(timeoutMarkerRepository.existsById(txId)).thenReturn(true);

        service().release("evt-1", txId, "vault:v1:tok");

        verify(timeoutMarkerRepository, never()).save(any());
    }

    @Test
    void anAuthorizedDeductionWithoutItsCardFailsLoudly() {
        notDuplicate();
        UUID txId = UUID.randomUUID();
        when(cardAccountRepository.findByCardTokenForUpdate("vault:v1:gone")).thenReturn(Optional.empty());
        when(authorizationRecordRepository.findByTransactionId(txId)).thenReturn(List.of(record(AuthorizationOutcome.AUTHORIZED)));

        assertThatThrownBy(() -> service().release("evt-1", txId, "vault:v1:gone"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no card account");
        verify(authorizationRecordRepository, never()).save(any());
    }
}
