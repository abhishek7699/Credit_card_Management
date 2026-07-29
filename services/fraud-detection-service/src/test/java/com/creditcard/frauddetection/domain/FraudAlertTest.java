package com.creditcard.frauddetection.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FraudAlertTest {

    @Test
    void shouldSetAndGetAllFields() {
        FraudAlert alert = new FraudAlert();
        UUID id = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        Instant now = Instant.now();

        alert.setId(id);
        alert.setTransactionId(txId);
        alert.setCardToken("vault:v1:tok");
        alert.setAmount(new BigDecimal("250.00"));
        alert.setCurrencyCode("USD");
        alert.setTriggeredRules(List.of("VELOCITY_EXCEEDED", "BLACKLISTED_CARD"));
        alert.setDetectedAt(now);

        assertThat(alert.getId()).isEqualTo(id);
        assertThat(alert.getTransactionId()).isEqualTo(txId);
        assertThat(alert.getCardToken()).isEqualTo("vault:v1:tok");
        assertThat(alert.getAmount()).isEqualByComparingTo("250.00");
        assertThat(alert.getCurrencyCode()).isEqualTo("USD");
        assertThat(alert.getTriggeredRules()).containsExactly("VELOCITY_EXCEEDED", "BLACKLISTED_CARD");
        assertThat(alert.getDetectedAt()).isEqualTo(now);
    }

    @Test
    void converterShouldConvertListToCsv() {
        FraudAlert.TriggeredRulesConverter converter = new FraudAlert.TriggeredRulesConverter();

        String csv = converter.convertToDatabaseColumn(List.of("VELOCITY_EXCEEDED", "AMOUNT_ANOMALY"));
        assertThat(csv).isEqualTo("VELOCITY_EXCEEDED,AMOUNT_ANOMALY");
    }

    @Test
    void converterShouldConvertCsvToList() {
        FraudAlert.TriggeredRulesConverter converter = new FraudAlert.TriggeredRulesConverter();

        List<String> list = converter.convertToEntityAttribute("BLACKLISTED_CARD,AMOUNT_ANOMALY");
        assertThat(list).containsExactly("BLACKLISTED_CARD", "AMOUNT_ANOMALY");
    }

    @Test
    void converterShouldHandleSingleElement() {
        FraudAlert.TriggeredRulesConverter converter = new FraudAlert.TriggeredRulesConverter();

        assertThat(converter.convertToDatabaseColumn(List.of("VELOCITY_EXCEEDED")))
                .isEqualTo("VELOCITY_EXCEEDED");
        assertThat(converter.convertToEntityAttribute("VELOCITY_EXCEEDED"))
                .containsExactly("VELOCITY_EXCEEDED");
    }

    @Test
    void converterShouldHandleNullAndEmpty() {
        FraudAlert.TriggeredRulesConverter converter = new FraudAlert.TriggeredRulesConverter();

        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToDatabaseColumn(List.of())).isEmpty();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("")).isEmpty();
    }
}
