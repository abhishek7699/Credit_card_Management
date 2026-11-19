package com.creditcard.transaction.integration;

import com.creditcard.transaction.repository.IdempotencyKeyRepository;
import com.creditcard.transaction.repository.TransactionRepository;
import com.creditcard.transaction.service.IdempotencyKeyService;
import com.creditcard.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultTransitOperations;
import org.springframework.vault.support.VaultTransitContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real Postgres: proves the claim-then-create replay against the actual ON CONFLICT
 * behavior, that a replay skips tokenization (Vault) and the Kafka event, and that the
 * expiry/purge rules hold on the real schema.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = {"transaction.initiated"}, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ActiveProfiles("test")
class IdempotencyKeyIntegrationTest {

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
    private TransactionRepository transactionRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private IdempotencyKeyService idempotencyKeyService;

    /** Stubs Vault's transit encrypt and returns the mock so tests can count tokenizations. */
    private VaultTransitOperations stubVault() {
        VaultTransitOperations transitOps = mock(VaultTransitOperations.class);
        when(vaultTemplate.opsForTransit()).thenReturn(transitOps);
        when(transitOps.encrypt(anyString(), any(byte[].class), any(VaultTransitContext.class)))
                .thenReturn("vault:v1:test-token");
        return transitOps;
    }

    private IdempotencyKeyService.StoredResult initiate(String key) {
        return transactionService.initiateTransaction(
                key, "4111111111111111", "Test User", new BigDecimal("50.00"), "USD", "merchant-001");
    }

    private static String freshKey() {
        return "it-" + UUID.randomUUID();
    }

    @Test
    void sameKeyTwiceReturnsSameTransactionAndCreatesOneRow() {
        VaultTransitOperations transitOps = stubVault();
        String key = freshKey();
        long before = transactionRepository.count();

        IdempotencyKeyService.StoredResult first = initiate(key);
        IdempotencyKeyService.StoredResult second = initiate(key);

        assertThat(second).isEqualTo(first);
        assertThat(second.status()).isEqualTo("PENDING");
        assertThat(transactionRepository.count()).isEqualTo(before + 1);
        // Replay skipped tokenization entirely: Vault was called exactly once.
        verify(transitOps, times(1)).encrypt(anyString(), any(byte[].class), any(VaultTransitContext.class));
    }

    @Test
    void differentKeysCreateSeparateTransactions() {
        stubVault();
        long before = transactionRepository.count();

        IdempotencyKeyService.StoredResult a = initiate(freshKey());
        IdempotencyKeyService.StoredResult b = initiate(freshKey());

        assertThat(a.resourceId()).isNotEqualTo(b.resourceId());
        assertThat(transactionRepository.count()).isEqualTo(before + 2);
    }

    @Test
    void noKeyCreatesAResourceEveryTime() {
        VaultTransitOperations transitOps = stubVault();
        long before = transactionRepository.count();

        IdempotencyKeyService.StoredResult a = initiate(null);
        IdempotencyKeyService.StoredResult b = initiate(null);

        assertThat(a.resourceId()).isNotEqualTo(b.resourceId());
        assertThat(transactionRepository.count()).isEqualTo(before + 2);
        verify(transitOps, times(2)).encrypt(anyString(), any(byte[].class), any(VaultTransitContext.class));
    }

    @Test
    @Transactional
    void expiredKeyIsReclaimedAndCreatesNewResource() {
        stubVault();
        String key = freshKey();
        Instant past = Instant.now().minusSeconds(60L * 60 * 25);
        idempotencyKeyRepository.insertIfAbsent(key, past, past);
        idempotencyKeyRepository.recordResult(key, UUID.randomUUID(), "PENDING");

        IdempotencyKeyService.StoredResult result = initiate(key);

        // The stale row was not replayed: a brand-new transaction was created.
        assertThat(transactionRepository.findById(result.resourceId())).isPresent();
    }

    @Test
    @Transactional
    void purgeRemovesExpiredKeysAndKeepsLiveOnes() {
        String expired = freshKey();
        String live = freshKey();
        Instant past = Instant.now().minusSeconds(60L * 60 * 25);
        idempotencyKeyRepository.insertIfAbsent(expired, past, past);
        idempotencyKeyRepository.insertIfAbsent(live, Instant.now(), Instant.now().plusSeconds(3600));

        idempotencyKeyService.purgeExpired();

        assertThat(idempotencyKeyRepository.findById(expired)).isEmpty();
        assertThat(idempotencyKeyRepository.findById(live)).isPresent();
    }
}
