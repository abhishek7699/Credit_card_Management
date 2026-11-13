package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import com.creditcard.authorization.service.AuthorizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Spec 014 T010: a timeout that ran before the in-flight authorization is blocked by its marker, and the
 * marker check happens only after the card row lock (013 ordering).
 */
@ExtendWith(MockitoExtension.class)
class SweepVsInFlightAuthorizeTest {

    @Mock
    private CardAccountRepository cardAccountRepository;

    @Mock
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Mock
    private TimeoutMarkerRepository timeoutMarkerRepository;

    private AuthorizationService service() {
        return new AuthorizationService(
                new SimulatedProcessor(cardAccountRepository, authorizationRecordRepository, timeoutMarkerRepository),
                authorizationRecordRepository);
    }

    @Test
    void aLaterAuthorizeAfterTheMarkerDoesNotDebitAndPublishesNothing() {
        TransactionInitiatedEvent event = event();
        UUID txId = event.getPayload().getTransactionId();
        CardAccount account = account("500.00");
        when(cardAccountRepository.findByCardTokenForUpdate("vault:v1:tok")).thenReturn(Optional.of(account));
        when(timeoutMarkerRepository.existsById(txId)).thenReturn(true);

        assertThat(service().authorize(event)).isEmpty();
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("500.00");
        verify(cardAccountRepository, never()).save(any());
        verify(authorizationRecordRepository, never()).save(any());
    }

    @Test
    void theCardLockIsTakenBeforeTheMarkerIsChecked() {
        TransactionInitiatedEvent event = event();
        when(cardAccountRepository.findByCardTokenForUpdate("vault:v1:tok")).thenReturn(Optional.empty());

        service().authorize(event);

        InOrder order = inOrder(cardAccountRepository, timeoutMarkerRepository);
        order.verify(cardAccountRepository).findByCardTokenForUpdate("vault:v1:tok");
        order.verify(timeoutMarkerRepository).existsById(event.getPayload().getTransactionId());
    }

    private static CardAccount account(String availableCredit) {
        CardAccount account = new CardAccount();
        account.setCardToken("vault:v1:tok");
        account.setStatus(CardAccountStatus.ACTIVE);
        account.setCreditLimit(new BigDecimal("1000.00"));
        account.setAvailableCredit(new BigDecimal(availableCredit));
        account.setCurrencyCode("USD");
        return account;
    }

    private static TransactionInitiatedEvent event() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType("TransactionInitiated");
        TransactionInitiatedEvent.Payload payload = new TransactionInitiatedEvent.Payload();
        payload.setTransactionId(UUID.randomUUID());
        payload.setCardToken("vault:v1:tok");
        payload.setMaskedCardNumber("****-****-****-1111");
        payload.setCardholderName("Jane Smith");
        payload.setAmount(new BigDecimal("100.00"));
        payload.setCurrencyCode("USD");
        payload.setMerchantId("merchant-1");
        event.setPayload(payload);
        return event;
    }
}
