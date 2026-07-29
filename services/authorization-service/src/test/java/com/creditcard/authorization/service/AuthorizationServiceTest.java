package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
import com.creditcard.authorization.domain.ReasonCode;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceTest {

    @Mock
    private CardAccountRepository cardAccountRepository;

    @Mock
    private AuthorizationRecordRepository authorizationRecordRepository;

    @InjectMocks
    private AuthorizationService authorizationService;

    @Test
    void shouldAuthorizeAndDeductCreditWhenCardActiveAndSufficient() {
        CardAccount account = buildAccount(CardAccountStatus.ACTIVE, "500.00");
        when(cardAccountRepository.findByCardToken("vault:v1:tok")).thenReturn(Optional.of(account));
        when(authorizationRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationResultEvent result = authorizationService.authorize(buildEvent("vault:v1:tok", "100.00"));

        assertThat(result.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(result.getReasonCode()).isNull();

        ArgumentCaptor<CardAccount> accountCaptor = ArgumentCaptor.forClass(CardAccount.class);
        verify(cardAccountRepository).save(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getAvailableCredit()).isEqualByComparingTo("400.00");

        ArgumentCaptor<AuthorizationRecord> recordCaptor = ArgumentCaptor.forClass(AuthorizationRecord.class);
        verify(authorizationRecordRepository).save(recordCaptor.capture());
        AuthorizationRecord saved = recordCaptor.getValue();
        assertThat(saved.getOutcome()).isEqualTo(AuthorizationOutcome.AUTHORIZED);
        assertThat(saved.getTransactionId()).isNotNull();
        assertThat(saved.getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(saved.getRequestedAmount()).isEqualByComparingTo("100.00");
        assertThat(saved.getCurrencyCode()).isEqualTo("USD");
        assertThat(saved.getReasonCode()).isNull();
        assertThat(saved.getDecidedAt()).isNotNull();
    }

    @Test
    void shouldDeclineWhenInsufficientCredit() {
        CardAccount account = buildAccount(CardAccountStatus.ACTIVE, "50.00");
        when(cardAccountRepository.findByCardToken("vault:v1:tok")).thenReturn(Optional.of(account));
        when(authorizationRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationResultEvent result = authorizationService.authorize(buildEvent("vault:v1:tok", "100.00"));

        assertThat(result.getEventType()).isEqualTo("TransactionDeclined");
        assertThat(result.getReasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_CREDIT.name());
        verify(cardAccountRepository, never()).save(any());
    }

    @Test
    void shouldDeclineWhenCardBlocked() {
        CardAccount account = buildAccount(CardAccountStatus.BLOCKED, "1000.00");
        when(cardAccountRepository.findByCardToken("vault:v1:tok")).thenReturn(Optional.of(account));
        when(authorizationRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationResultEvent result = authorizationService.authorize(buildEvent("vault:v1:tok", "100.00"));

        assertThat(result.getEventType()).isEqualTo("TransactionDeclined");
        assertThat(result.getReasonCode()).isEqualTo(ReasonCode.CARD_NOT_ACTIVE.name());
        verify(cardAccountRepository, never()).save(any());
    }

    @Test
    void shouldDeclineWhenCardExpired() {
        CardAccount account = buildAccount(CardAccountStatus.EXPIRED, "1000.00");
        when(cardAccountRepository.findByCardToken("vault:v1:tok")).thenReturn(Optional.of(account));
        when(authorizationRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationResultEvent result = authorizationService.authorize(buildEvent("vault:v1:tok", "100.00"));

        assertThat(result.getEventType()).isEqualTo("TransactionDeclined");
        assertThat(result.getReasonCode()).isEqualTo(ReasonCode.CARD_NOT_ACTIVE.name());
    }

    @Test
    void shouldReturnFailedEventForFailedOutcome() {
        UUID txId = UUID.randomUUID();
        TransactionInitiatedEvent event = buildEvent("vault:v1:tok", "100.00");
        event.setTransactionId(txId);
        when(authorizationRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationResultEvent result = authorizationService.persistAndReturn(
                event, AuthorizationOutcome.FAILED, ReasonCode.INTERNAL_ERROR);

        assertThat(result.getEventType()).isEqualTo("TransactionFailed");
        assertThat(result.getReasonCode()).isEqualTo(ReasonCode.INTERNAL_ERROR.name());
        assertThat(result.getTransactionId()).isEqualTo(txId);
    }

    @Test
    void shouldDeclineWhenCardTokenNotFound() {
        when(cardAccountRepository.findByCardToken("vault:v1:unknown")).thenReturn(Optional.empty());
        when(authorizationRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthorizationResultEvent result = authorizationService.authorize(buildEvent("vault:v1:unknown", "100.00"));

        assertThat(result.getEventType()).isEqualTo("TransactionDeclined");
        assertThat(result.getReasonCode()).isEqualTo(ReasonCode.INVALID_CARD.name());
        verify(cardAccountRepository, never()).save(any());
    }

    private CardAccount buildAccount(CardAccountStatus status, String availableCredit) {
        CardAccount account = new CardAccount();
        account.setCardToken("vault:v1:tok");
        account.setStatus(status);
        account.setCreditLimit(new BigDecimal("1000.00"));
        account.setAvailableCredit(new BigDecimal(availableCredit));
        account.setCurrencyCode("USD");
        return account;
    }

    private TransactionInitiatedEvent buildEvent(String token, String amount) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionInitiated");
        event.setTransactionId(UUID.randomUUID());
        event.setCardToken(token);
        event.setAmount(new BigDecimal(amount));
        event.setCurrencyCode("USD");
        event.setOccurredAt(Instant.now());
        return event;
    }
}
