package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec 014 T009: releaseHold with no authorization is a safe no-op (013 release semantics). */
@ExtendWith(MockitoExtension.class)
class ReleaseHoldNoOpTest {

    @Mock
    private CardAccountRepository cardAccountRepository;

    @Mock
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Test
    void noRecordReturnsNoAuthorizationAndChangesNoCredit() {
        UUID txId = UUID.randomUUID();
        CardAccount account = account("400.00");
        when(authorizationRecordRepository.findByTransactionId(txId)).thenReturn(List.of());

        ProcessorDecision decision = new SimulatedProcessor(cardAccountRepository, authorizationRecordRepository)
                .releaseHold(txId, account);

        assertThat(decision.getOutcome()).isEqualTo(ProcessorOutcome.NO_AUTHORIZATION);
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("400.00");
        verify(cardAccountRepository, never()).save(any());
        verify(authorizationRecordRepository, never()).save(any());
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
}
