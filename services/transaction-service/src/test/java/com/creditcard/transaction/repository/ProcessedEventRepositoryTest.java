package com.creditcard.transaction.repository;

import com.creditcard.transaction.domain.ProcessedEvent;
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

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ProcessedEventRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void shouldSaveAndFindProcessedEvent() {
        String key = "tx:TransactionAuthorized:550e8400-e29b-41d4-a716-446655440000";
        ProcessedEvent event = new ProcessedEvent(key);

        processedEventRepository.save(event);

        assertThat(processedEventRepository.existsByEventKey(key)).isTrue();
    }

    @Test
    void shouldReturnFalseForUnseenEventKey() {
        assertThat(processedEventRepository.existsByEventKey("tx:TransactionAuthorized:unknown-id")).isFalse();
    }

    @Test
    void shouldSupportDifferentEventTypes() {
        String authorizedKey = "tx:TransactionAuthorized:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        String declinedKey   = "tx:TransactionDeclined:aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

        processedEventRepository.save(new ProcessedEvent(authorizedKey));
        processedEventRepository.save(new ProcessedEvent(declinedKey));

        assertThat(processedEventRepository.existsByEventKey(authorizedKey)).isTrue();
        assertThat(processedEventRepository.existsByEventKey(declinedKey)).isTrue();
    }

    @Test
    void secondSaveWithSameKeyShouldNotCreateDuplicate() {
        String key = "tx:TransactionFailed:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

        processedEventRepository.save(new ProcessedEvent(key));

        long count = processedEventRepository.count();
        assertThat(count).isEqualTo(1);
        assertThat(processedEventRepository.existsByEventKey(key)).isTrue();
    }
}
