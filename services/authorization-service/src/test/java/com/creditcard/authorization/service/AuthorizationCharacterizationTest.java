package com.creditcard.authorization.service;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins today's approval outcomes (spec 014 phase 1, T002). Written against the unchanged code and
 * kept unchanged through the refactor. The only line that changes at refactor time is {@link #service()}.
 */
@ExtendWith(MockitoExtension.class)
class AuthorizationCharacterizationTest {

    private static final String TOKEN = "vault:v1:tok";

    @Mock
    private CardAccountRepository cardAccountRepository;

    @Mock
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Mock
    private TimeoutMarkerRepository timeoutMarkerRepository;

    @BeforeEach
    void noTimeoutMarker() {
        when(authorizationRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private AuthorizationService service() {
        return new AuthorizationService(
                new com.creditcard.authorization.processor.SimulatedProcessor(
                        cardAccountRepository, authorizationRecordRepository),
                cardAccountRepository, authorizationRecordRepository, timeoutMarkerRepository);
    }

    @Test
    void approvesWhenActiveAndSufficientCredit() {
        CardAccount account = account(CardAccountStatus.ACTIVE, "500.00");
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account));

        AuthorizationResultEvent result = service().authorize(event("100.00")).orElseThrow();

        assertThat(result.getEventType()).isEqualTo("TransactionAuthorized");
        assertThat(result.getPayload().getOutcome()).isEqualTo("AUTHORIZED");
        assertThat(result.getPayload().getReasonCode()).isNull();
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("400.00");
        verify(cardAccountRepository).save(account);
        assertThat(savedRecord().getOutcome()).isEqualTo(AuthorizationOutcome.AUTHORIZED);
    }

    @Test
    void declinesWithInsufficientCreditAndLeavesCreditUnchanged() {
        CardAccount account = account(CardAccountStatus.ACTIVE, "50.00");
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account));

        AuthorizationResultEvent result = service().authorize(event("100.00")).orElseThrow();

        assertThat(result.getPayload().getOutcome()).isEqualTo("DECLINED");
        assertThat(result.getPayload().getReasonCode()).isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("50.00");
        verify(cardAccountRepository, never()).save(any());
        assertThat(savedRecord().getOutcome()).isEqualTo(AuthorizationOutcome.DECLINED);
    }

    @Test
    void declinesABlockedCardWithCardNotActive() {
        CardAccount account = account(CardAccountStatus.BLOCKED, "500.00");
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.of(account));

        AuthorizationResultEvent result = service().authorize(event("100.00")).orElseThrow();

        assertThat(result.getPayload().getOutcome()).isEqualTo("DECLINED");
        assertThat(result.getPayload().getReasonCode()).isEqualTo("CARD_NOT_ACTIVE");
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("500.00");
        verify(cardAccountRepository, never()).save(any());
    }

    @Test
    void declinesAnUnknownCardWithInvalidCardAndWritesNoAccount() {
        when(cardAccountRepository.findByCardTokenForUpdate(TOKEN)).thenReturn(Optional.empty());

        AuthorizationResultEvent result = service().authorize(event("100.00")).orElseThrow();

        assertThat(result.getPayload().getOutcome()).isEqualTo("DECLINED");
        assertThat(result.getPayload().getReasonCode()).isEqualTo("INVALID_CARD");
        verify(cardAccountRepository, never()).save(any());
        assertThat(savedRecord().getOutcome()).isEqualTo(AuthorizationOutcome.DECLINED);
    }

    private AuthorizationRecord savedRecord() {
        ArgumentCaptor<AuthorizationRecord> captor = ArgumentCaptor.forClass(AuthorizationRecord.class);
        verify(authorizationRecordRepository).save(captor.capture());
        return captor.getValue();
    }

    private static CardAccount account(CardAccountStatus status, String availableCredit) {
        CardAccount account = new CardAccount();
        account.setCardToken(TOKEN);
        account.setStatus(status);
        account.setCreditLimit(new BigDecimal("1000.00"));
        account.setAvailableCredit(new BigDecimal(availableCredit));
        account.setCurrencyCode("USD");
        return account;
    }

    private static TransactionInitiatedEvent event(String amount) {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionInitiated");
        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setCardToken(TOKEN);
        payload.setMaskedCardNumber("****-****-****-1111");
        payload.setCardholderName("Jane Smith");
        payload.setAmount(new BigDecimal(amount));
        payload.setCurrencyCode("USD");
        payload.setMerchantId("merchant-1");
        event.setPayload(payload);
        return event;
    }
}
