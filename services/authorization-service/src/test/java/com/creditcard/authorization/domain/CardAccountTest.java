package com.creditcard.authorization.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CardAccountTest {

    @Test
    void shouldSetAndGetAllFields() {
        CardAccount account = new CardAccount();
        UUID id = UUID.randomUUID();
        account.setId(id);
        account.setCardToken("vault:v1:opaque-token");
        account.setStatus(CardAccountStatus.ACTIVE);
        account.setCreditLimit(new BigDecimal("5000.00"));
        account.setAvailableCredit(new BigDecimal("4900.00"));
        account.setCurrencyCode("USD");
        account.setVersion(0L);

        assertThat(account.getId()).isEqualTo(id);
        assertThat(account.getCardToken()).isEqualTo("vault:v1:opaque-token");
        assertThat(account.getStatus()).isEqualTo(CardAccountStatus.ACTIVE);
        assertThat(account.getCreditLimit()).isEqualByComparingTo("5000.00");
        assertThat(account.getAvailableCredit()).isEqualByComparingTo("4900.00");
        assertThat(account.getCurrencyCode()).isEqualTo("USD");
        assertThat(account.getVersion()).isZero();
    }

    @Test
    void onCreateShouldSetBothTimestamps() {
        CardAccount account = new CardAccount();
        account.onCreate();

        assertThat(account.getCreatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isNotNull();
    }

    @Test
    void shouldSetAndGetTimestampFields() {
        CardAccount account = new CardAccount();
        Instant created = Instant.now();
        Instant updated = Instant.now();

        account.setCreatedAt(created);
        account.setUpdatedAt(updated);

        assertThat(account.getCreatedAt()).isEqualTo(created);
        assertThat(account.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void onUpdateShouldSetUpdatedAtWithoutChangingCreatedAt() {
        CardAccount account = new CardAccount();
        account.onCreate();
        Instant created = account.getCreatedAt();

        account.onUpdate();

        assertThat(account.getUpdatedAt()).isNotNull();
        assertThat(account.getCreatedAt()).isEqualTo(created);
    }
}
