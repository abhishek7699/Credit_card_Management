package com.creditcard.transaction;

import com.creditcard.transaction.repository.ProcessedEventRepository;
import com.creditcard.transaction.repository.TransactionRepository;
import com.creditcard.transaction.service.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction.initiated", "transaction.status.updated"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@ActiveProfiles("test")
class TransactionServiceApplicationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Replace real VaultTemplate so the context loads without a live Vault server
    @MockBean
    VaultTemplate vaultTemplate;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @Test
    void contextLoads() {
        assertThat(transactionRepository).isNotNull();
        assertThat(processedEventRepository).isNotNull();
        assertThat(idempotencyService).isNotNull();
    }
}
