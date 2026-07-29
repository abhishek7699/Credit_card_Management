package com.creditcard.notification.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationServiceTest {

    @Test
    void maskEmailFormatsCorrectly() {
        assertThat(NotificationService.maskEmail("test@example.com")).isEqualTo("t***@example.com");
    }

    @Test
    void maskEmailSingleCharLocalPart() {
        assertThat(NotificationService.maskEmail("a@domain.com")).isEqualTo("a***@domain.com");
    }

    @Test
    void maskPhoneFormatsCorrectly() {
        assertThat(NotificationService.maskPhone("+14155550000")).isEqualTo("***-***-0000");
    }

    @Test
    void maskPhoneShortNumber() {
        assertThat(NotificationService.maskPhone("+15678")).isEqualTo("***-***-5678");
    }

    @Test
    void last4ExtractsFromMaskedCard() {
        assertThat(NotificationService.last4("****-****-****-5678")).isEqualTo("5678");
    }

    @Test
    void buildTransactionSubjectAuthorized() {
        assertThat(NotificationService.buildTransactionSubject("TransactionAuthorized"))
                .isEqualTo("Payment Approved");
    }

    @Test
    void buildTransactionSubjectDeclined() {
        assertThat(NotificationService.buildTransactionSubject("TransactionDeclined"))
                .isEqualTo("Payment Declined");
    }

    @Test
    void buildTransactionSubjectFailed() {
        assertThat(NotificationService.buildTransactionSubject("TransactionFailed"))
                .isEqualTo("Payment Failed");
    }

    @Test
    void buildTransactionBodyAuthorizedContainsLast4AndTransactionId() {
        String body = NotificationService.buildTransactionBody(
                "TransactionAuthorized", "5678", "tx-001", null);
        assertThat(body).contains("5678").contains("tx-001").contains("approved");
    }

    @Test
    void buildTransactionBodyDeclinedContainsReasonCode() {
        String body = NotificationService.buildTransactionBody(
                "TransactionDeclined", "5678", "tx-002", "INSUFFICIENT_CREDIT");
        assertThat(body).contains("5678").contains("INSUFFICIENT_CREDIT").contains("declined");
    }

    @Test
    void buildTransactionBodyDoesNotContainRawPanPattern() {
        String body = NotificationService.buildTransactionBody(
                "TransactionAuthorized", "5678", "tx-003", null);
        assertThat(body).doesNotMatch(".*\\d{13,19}.*");
    }

    @Test
    void buildFraudAlertSubject() {
        assertThat(NotificationService.buildFraudAlertSubject())
                .isEqualTo("URGENT: Suspicious Activity Detected");
    }

    @Test
    void buildFraudAlertEmailBodyContainsLast4AndRulesAndAlertId() {
        String body = NotificationService.buildFraudAlertEmailBody(
                "5678", java.util.List.of("VELOCITY_EXCEEDED", "BLACKLISTED_CARD"), "alert-001");
        assertThat(body)
                .contains("5678")
                .contains("VELOCITY_EXCEEDED")
                .contains("BLACKLISTED_CARD")
                .contains("alert-001")
                .contains("Suspicious activity");
    }

    @Test
    void buildFraudAlertSmsBodyContainsLast4AndRules() {
        String body = NotificationService.buildFraudAlertSmsBody(
                "5678", java.util.List.of("AMOUNT_ANOMALY"));
        assertThat(body).contains("5678").contains("AMOUNT_ANOMALY");
    }

    @Test
    void buildFraudAlertEmailBodyConsolidatesMultipleRulesIntoOneMessage() {
        String body = NotificationService.buildFraudAlertEmailBody(
                "1234", java.util.List.of("VELOCITY_EXCEEDED", "AMOUNT_ANOMALY", "BLACKLISTED_CARD"), "a-1");
        long ruleOccurrences = java.util.Arrays.stream(body.split("VELOCITY_EXCEEDED")).count() - 1;
        assertThat(ruleOccurrences).isEqualTo(1);
    }

    // ── Edge cases for helper methods ──────────────────────────────────────────

    @Test
    void maskEmail_atSignAtPositionZero_returnsObfuscated() {
        assertThat(NotificationService.maskEmail("@example.com")).isEqualTo("***");
    }

    @Test
    void maskPhone_fewerThanFourDigits_returnsDigitsDirectly() {
        assertThat(NotificationService.maskPhone("+1")).isEqualTo("***-***-1");
    }

    @Test
    void last4_nullInput_returnsNull() {
        assertThat(NotificationService.last4(null)).isNull();
    }

    @Test
    void last4_shortString_returnsFullString() {
        assertThat(NotificationService.last4("123")).isEqualTo("123");
    }
}
