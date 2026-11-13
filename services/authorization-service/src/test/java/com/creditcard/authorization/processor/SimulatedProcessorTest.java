package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Branch coverage for the simulator: decide, lookup, capture and releaseHold. */
@ExtendWith(MockitoExtension.class)
class SimulatedProcessorTest {

    private static final String TOKEN = "vault:v1:tok";

    @Mock
    private CardAccountRepository cardAccountRepository;

    @Mock
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Mock
    private TimeoutMarkerRepository timeoutMarkerRepository;

    private SimulatedProcessor processor() {
        return new SimulatedProcessor(cardAccountRepository, authorizationRecordRepository, timeoutMarkerRepository);
    }

    @Test
    void authorizeSkipsWhenATimeoutMarkerExists() {
        UUID txId = UUID.randomUUID();
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account("500.00")));
        when(timeoutMarkerRepository.existsById(txId)).thenReturn(true);

        assertThat(processor().authorize(request(txId, "10.00")).getOutcome())
                .isEqualTo(ProcessorOutcome.TIMED_OUT_SKIPPED);
        verify(cardAccountRepository, never()).save(any());
    }

    @Test
    void authorizeDeclinesWhenTheCardIsUnknown() {
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.empty());

        ProcessorDecision decision = processor().authorize(request(UUID.randomUUID(), "10.00"));

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.DECLINED);
        assertThat(decision.getReasonCode()).isEqualTo("INVALID_CARD");
    }

    @Test
    void authorizeDeclinesABlockedCard() {
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN))
                .thenReturn(Optional.of(account("500.00", CardAccountStatus.BLOCKED)));

        ProcessorDecision decision = processor().authorize(request(UUID.randomUUID(), "10.00"));

        assertThat(decision.getReasonCode()).isEqualTo("CARD_NOT_ACTIVE");
    }

    @Test
    void authorizeDeclinesOnInsufficientCredit() {
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account("5.00")));

        ProcessorDecision decision = processor().authorize(request(UUID.randomUUID(), "10.00"));

        assertThat(decision.getReasonCode()).isEqualTo("INSUFFICIENT_CREDIT");
        verify(cardAccountRepository, never()).save(any());
    }

    @Test
    void authorizeApprovesAndDebitsTheCard() {
        CardAccount account = account("500.00");
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account));

        ProcessorDecision decision = processor().authorize(request(UUID.randomUUID(), "100.00"));

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.APPROVED);
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("400.00");
        verify(cardAccountRepository).save(account);
    }

    @Test
    void lookupIsAlwaysUnknownForTheSimulator() {
        assertThat(processor().lookup(UUID.randomUUID()).getOutcome()).isEqualTo(ProcessorOutcome.UNKNOWN);
    }

    @Test
    void captureIsAlwaysRejectedForTheSimulator() {
        ProcessorDecision decision = processor().capture(UUID.randomUUID(), BigDecimal.ONE, "USD");
        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.CAPTURE_REJECTED);
        assertThat(decision.getReasonCode()).isEqualTo("SIMULATOR_NO_CAPTURE");
    }

    @Test
    void releaseRestoresEachAuthorizedAmountAndSkipsOtherRecords() {
        UUID txId = UUID.randomUUID();
        CardAccount account = account("400.00");
        AuthorizationRecord declined = record(txId, AuthorizationOutcome.DECLINED, "5.00");
        AuthorizationRecord authorized = record(txId, AuthorizationOutcome.AUTHORIZED, "100.00");
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account));
        when(authorizationRecordRepository.findByTransactionId(txId)).thenReturn(List.of(declined, authorized));

        ProcessorDecision decision = processor().releaseHold(txId, TOKEN);

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.RELEASED);
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("500.00");
        assertThat(authorized.getOutcome()).isEqualTo(AuthorizationOutcome.RELEASED);
        assertThat(declined.getOutcome()).isEqualTo(AuthorizationOutcome.DECLINED);
    }

    @Test
    void releaseWithOnlyDeclinedRecordsIsANoOp() {
        UUID txId = UUID.randomUUID();
        CardAccount account = account("400.00");
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account));
        when(authorizationRecordRepository.findByTransactionId(txId))
                .thenReturn(List.of(record(txId, AuthorizationOutcome.DECLINED, "5.00")));

        ProcessorDecision decision = processor().releaseHold(txId, TOKEN);

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.NO_OP);
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("400.00");
        verify(cardAccountRepository, never()).save(any());
    }

    @Test
    void releaseOfAnAuthorizedRecordWithoutACardRowFails() {
        UUID txId = UUID.randomUUID();
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.empty());
        when(authorizationRecordRepository.findByTransactionId(txId))
                .thenReturn(List.of(record(txId, AuthorizationOutcome.AUTHORIZED, "100.00")));

        assertThatThrownBy(() -> processor().releaseHold(txId, TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no card account for the authorized deduction");
    }

    private static AuthorizeRequest request(UUID txId, String amount) {
        return new AuthorizeRequest(txId, new BigDecimal(amount), "USD", "merchant-1", TOKEN);
    }

    private static CardAccount account(String availableCredit) {
        return account(availableCredit, CardAccountStatus.ACTIVE);
    }

    private static CardAccount account(String availableCredit, CardAccountStatus status) {
        CardAccount account = new CardAccount();
        account.setCardToken(TOKEN);
        account.setStatus(status);
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
