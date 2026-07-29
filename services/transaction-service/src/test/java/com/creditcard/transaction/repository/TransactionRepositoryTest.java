package com.creditcard.transaction.repository;

import com.creditcard.transaction.domain.Transaction;
import com.creditcard.transaction.domain.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TransactionRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TransactionRepository transactionRepository;

    private Transaction buildTransaction() {
        Transaction tx = new Transaction();
        tx.setCardToken("vault:v1:abc123xyz");
        tx.setMaskedCardNumber("****-****-****-9876");
        tx.setCardholderName("Jane Doe");
        tx.setAmount(new BigDecimal("150.00"));
        tx.setCurrencyCode("USD");
        tx.setStatus(TransactionStatus.PENDING);
        return tx;
    }

    @Test
    void shouldSaveAndFindTransactionById() {
        Transaction saved = transactionRepository.save(buildTransaction());

        Optional<Transaction> found = transactionRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getCardToken()).isEqualTo("vault:v1:abc123xyz");
        assertThat(found.get().getMaskedCardNumber()).isEqualTo("****-****-****-9876");
        assertThat(found.get().getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void shouldUpdateStatusById() {
        Transaction saved = transactionRepository.save(buildTransaction());

        int updated = transactionRepository.updateStatusById(saved.getId(), TransactionStatus.AUTHORIZED);

        assertThat(updated).isEqualTo(1);
        Transaction reloaded = transactionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(TransactionStatus.AUTHORIZED);
    }

    @Test
    void shouldReturnZeroWhenUpdatingNonExistentTransaction() {
        int updated = transactionRepository.updateStatusById(UUID.randomUUID(), TransactionStatus.AUTHORIZED);

        assertThat(updated).isZero();
    }

    @Test
    void shouldNotStoreRawCardNumber() {
        Transaction saved = transactionRepository.save(buildTransaction());

        Transaction found = transactionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getCardToken()).startsWith("vault:");
        assertThat(found.getCardToken()).doesNotMatch("^\\d{13,19}$");
    }
}
