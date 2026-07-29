package com.creditcard.authorization.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRecordTest {

    @Test
    void shouldSetAndGetAllFields() {
        UUID id = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        Instant now = Instant.now();

        AuthorizationRecord record = new AuthorizationRecord();
        record.setId(id);
        record.setTransactionId(transactionId);
        record.setCardToken("vault:v1:token");
        record.setRequestedAmount(new BigDecimal("99.99"));
        record.setCurrencyCode("USD");
        record.setOutcome(AuthorizationOutcome.AUTHORIZED);
        record.setReasonCode(null);
        record.setDecidedAt(now);

        assertThat(record.getId()).isEqualTo(id);
        assertThat(record.getTransactionId()).isEqualTo(transactionId);
        assertThat(record.getCardToken()).isEqualTo("vault:v1:token");
        assertThat(record.getRequestedAmount()).isEqualByComparingTo("99.99");
        assertThat(record.getCurrencyCode()).isEqualTo("USD");
        assertThat(record.getOutcome()).isEqualTo(AuthorizationOutcome.AUTHORIZED);
        assertThat(record.getReasonCode()).isNull();
        assertThat(record.getDecidedAt()).isEqualTo(now);
    }

    @Test
    void declinedRecordShouldHaveReasonCode() {
        AuthorizationRecord record = new AuthorizationRecord();
        record.setOutcome(AuthorizationOutcome.DECLINED);
        record.setReasonCode(ReasonCode.INSUFFICIENT_CREDIT);

        assertThat(record.getOutcome()).isEqualTo(AuthorizationOutcome.DECLINED);
        assertThat(record.getReasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_CREDIT);
    }

    @Test
    void failedRecordShouldHaveReasonCode() {
        AuthorizationRecord record = new AuthorizationRecord();
        record.setOutcome(AuthorizationOutcome.FAILED);
        record.setReasonCode(ReasonCode.INTERNAL_ERROR);

        assertThat(record.getOutcome()).isEqualTo(AuthorizationOutcome.FAILED);
        assertThat(record.getReasonCode()).isEqualTo(ReasonCode.INTERNAL_ERROR);
    }
}
