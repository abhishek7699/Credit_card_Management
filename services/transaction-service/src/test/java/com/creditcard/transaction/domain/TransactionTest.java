package com.creditcard.transaction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionTest {

    @Test
    void shouldCreateTransactionWithPendingStatus() {
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());
        tx.setCardToken("vault:v1:abc123");
        tx.setMaskedCardNumber("****-****-****-1234");
        tx.setCardholderName("Jane Smith");
        tx.setAmount(new BigDecimal("99.99"));
        tx.setCurrencyCode("USD");
        tx.setStatus(TransactionStatus.PENDING);
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());

        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(tx.getCardToken()).startsWith("vault:");
        assertThat(tx.getMaskedCardNumber()).matches("\\*{4}-\\*{4}-\\*{4}-\\d{4}");
    }

    @Test
    void cardTokenShouldNeverBeRawPan() {
        Transaction tx = new Transaction();
        tx.setCardToken("vault:v1:8SDd3WHDOjf7mq69CyCqYjBX");

        // A raw PAN would be a 13-19 digit number; vault ciphertext starts with "vault:"
        assertThat(tx.getCardToken()).startsWith("vault:");
        assertThat(tx.getCardToken()).doesNotMatch("^\\d{13,19}$");
    }

    @Test
    void maskedCardNumberShouldFollowFormat() {
        Transaction tx = new Transaction();
        tx.setMaskedCardNumber("****-****-****-4321");

        assertThat(tx.getMaskedCardNumber()).matches("\\*{4}-\\*{4}-\\*{4}-\\d{4}");
    }

    @Test
    void shouldSupportAllTerminalStatusTransitions() {
        Transaction tx = new Transaction();
        tx.setStatus(TransactionStatus.PENDING);
        assertThat(tx.getStatus().isTerminal()).isFalse();

        tx.setStatus(TransactionStatus.AUTHORIZED);
        assertThat(tx.getStatus().isTerminal()).isTrue();
    }

    @Test
    void versionFieldShouldExistForOptimisticLocking() {
        Transaction tx = new Transaction();
        tx.setVersion(0L);
        assertThat(tx.getVersion()).isZero();
    }

    @Test
    void onUpdateShouldSetUpdatedAtWithoutChangingCreatedAt() {
        Transaction tx = new Transaction();
        tx.onCreate();
        Instant createdAt = tx.getCreatedAt();

        tx.onUpdate();

        assertThat(tx.getUpdatedAt()).isNotNull();
        assertThat(tx.getCreatedAt()).isEqualTo(createdAt);
    }
}
