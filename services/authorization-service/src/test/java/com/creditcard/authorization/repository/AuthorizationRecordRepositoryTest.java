package com.creditcard.authorization.repository;

import com.creditcard.authorization.domain.AuthorizationOutcome;
import com.creditcard.authorization.domain.AuthorizationRecord;
import com.creditcard.authorization.domain.ReasonCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AuthorizationRecordRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private AuthorizationRecordRepository authorizationRecordRepository;

    @Test
    void shouldSaveAndRetrieveAuthorizedRecord() {
        AuthorizationRecord record = buildRecord(AuthorizationOutcome.AUTHORIZED, null);
        AuthorizationRecord saved = authorizationRecordRepository.save(record);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOutcome()).isEqualTo(AuthorizationOutcome.AUTHORIZED);
        assertThat(saved.getReasonCode()).isNull();
    }

    @Test
    void shouldSaveDeclinedRecordWithReasonCode() {
        AuthorizationRecord record = buildRecord(AuthorizationOutcome.DECLINED, ReasonCode.INSUFFICIENT_CREDIT);
        AuthorizationRecord saved = authorizationRecordRepository.save(record);

        assertThat(saved.getOutcome()).isEqualTo(AuthorizationOutcome.DECLINED);
        assertThat(saved.getReasonCode()).isEqualTo(ReasonCode.INSUFFICIENT_CREDIT);
    }

    @Test
    void shouldFindByTransactionId() {
        UUID txId = UUID.randomUUID();
        authorizationRecordRepository.save(buildRecordForTx(txId, AuthorizationOutcome.AUTHORIZED, null));

        List<AuthorizationRecord> records = authorizationRecordRepository.findByTransactionId(txId);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).getTransactionId()).isEqualTo(txId);
    }

    @Test
    void shouldReturnEmptyListForUnknownTransactionId() {
        List<AuthorizationRecord> records = authorizationRecordRepository.findByTransactionId(UUID.randomUUID());
        assertThat(records).isEmpty();
    }

    private AuthorizationRecord buildRecord(AuthorizationOutcome outcome, ReasonCode reasonCode) {
        return buildRecordForTx(UUID.randomUUID(), outcome, reasonCode);
    }

    private AuthorizationRecord buildRecordForTx(UUID txId, AuthorizationOutcome outcome, ReasonCode reasonCode) {
        AuthorizationRecord record = new AuthorizationRecord();
        record.setTransactionId(txId);
        record.setCardToken("vault:v1:test-token");
        record.setRequestedAmount(new BigDecimal("100.00"));
        record.setCurrencyCode("USD");
        record.setOutcome(outcome);
        record.setReasonCode(reasonCode);
        record.setDecidedAt(Instant.now());
        return record;
    }
}
