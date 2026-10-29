package com.creditcard.authorization.repository;

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

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProcessedEventRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void shouldInsertNewEventKeyAndReturnOne() {
        int rows = processedEventRepository.insertIfAbsent("auth:TransactionInitiated:evt-001");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void shouldReturnZeroForDuplicateEventKey() {
        String key = "auth:TransactionInitiated:evt-002";
        processedEventRepository.insertIfAbsent(key);

        int rows = processedEventRepository.insertIfAbsent(key);
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void shouldReturnTrueForAlreadyProcessedKey() {
        String key = "auth:TransactionInitiated:evt-003";
        processedEventRepository.insertIfAbsent(key);

        boolean processed = processedEventRepository.existsByEventKey(key);
        assertThat(processed).isTrue();
    }

    @Test
    void shouldReturnFalseForUnseenKey() {
        boolean processed = processedEventRepository.existsByEventKey("auth:TransactionInitiated:evt-never-seen");
        assertThat(processed).isFalse();
    }
}
