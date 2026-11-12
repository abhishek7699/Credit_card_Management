package com.creditcard.transaction.integration;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.repository.OutboxRepository;
import com.creditcard.transaction.repository.TransactionRepository;
import com.creditcard.transaction.service.TransactionService;
import com.creditcard.transaction.service.TransactionTimeoutSweep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 013 PENDING timeout against real Postgres. The scheduled sweep is off (test profile), so each step is
 * driven by the test. Rows are aged with SQL so they are past P without waiting five minutes.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction.status.updated", "transaction.timed_out", "transaction.authorized"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@ActiveProfiles("test")
class PendingTimeoutIntegrationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean
    private VaultTemplate vaultTemplate;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionTimeoutSweep sweep;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aStuckPendingTransactionTimesOutOnceWithOneFinalEventAndOneTimedOutEvent() {
        UUID txId = saveTransaction();
        ageBeyondTimeout(txId);

        assertThat(sweep.sweepOnce()).isEqualTo(1);

        Transaction tx = transactionRepository.findById(txId).orElseThrow();
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(tx.getFailureReason()).isEqualTo("TIMEOUT");
        assertThat(outboxRepository.findById(stable(txId, "transaction.status.updated", "TransactionFailed")).orElseThrow()
                .getSentAt()).isNotNull();
        assertThat(outboxRepository.findById(stable(txId, "transaction.timed_out", "TransactionTimedOut")).orElseThrow()
                .getSentAt()).isNotNull();

        // Nothing left to time out, and no second set of events.
        assertThat(sweep.sweepOnce()).isZero();
        assertThat(outboxCount(txId)).isEqualTo(2);
    }

    @Test
    void aLateAuthorizedResultAfterTheTimeoutChangesNothing() {
        UUID txId = saveTransaction();
        ageBeyondTimeout(txId);
        sweep.sweepOnce();

        transactionService.applyAuthorizationResult(txId.toString(), "AUTHORIZED", null);

        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(outboxRepository.findById(stable(txId, "transaction.authorized", "TransactionAuthorized"))).isEmpty();
        assertThat(outboxRepository.findById(stable(txId, "transaction.status.updated", "TransactionAuthorized"))).isEmpty();
    }

    @Test
    void aYoungPendingTransactionIsLeftAlone() {
        UUID txId = saveTransaction();

        assertThat(sweep.sweepOnce()).isZero();

        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void timingOutAFinishedTransactionIsANoOp() {
        UUID txId = saveTransaction();
        transactionService.applyAuthorizationResult(txId.toString(), "DECLINED", "51");

        assertThat(transactionService.timeOutPending(txId, Instant.now())).isFalse();

        assertThat(transactionRepository.findById(txId).orElseThrow().getStatus()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(transactionRepository.findById(txId).orElseThrow().getFailureReason()).isNull();
    }

    private UUID stable(UUID txId, String topic, String eventType) {
        return TransactionService.stableEventId(txId, topic, eventType);
    }

    private long outboxCount(UUID txId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_outbox WHERE message_key = ?", Long.class, txId.toString());
    }

    /** Moves created_at 400 s into the past: beyond P (300 s), so the sweep may act on it. */
    private void ageBeyondTimeout(UUID txId) {
        jdbcTemplate.update("UPDATE transactions SET created_at = now() - interval '400 seconds' WHERE id = ?", txId);
    }

    private UUID saveTransaction() {
        Transaction tx = new Transaction();
        tx.setCardToken("vault:v1:timeout-test");
        tx.setMaskedCardNumber("****-****-****-4242");
        tx.setCardholderName("Timeout Test");
        tx.setAmount(new BigDecimal("12.34"));
        tx.setCurrencyCode("USD");
        tx.setMerchantId("merchant-timeout");
        tx.setStatus(TransactionStatus.PENDING);
        return transactionRepository.save(tx).getId();
    }
}
