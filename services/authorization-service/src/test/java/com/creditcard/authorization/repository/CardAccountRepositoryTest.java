package com.creditcard.authorization.repository;

import com.creditcard.authorization.domain.CardAccount;
import com.creditcard.authorization.domain.CardAccountStatus;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CardAccountRepositoryTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private CardAccountRepository cardAccountRepository;

    @Test
    void shouldFindByCardToken() {
        CardAccount account = buildAccount("vault:v1:test-token-1");
        cardAccountRepository.save(account);

        Optional<CardAccount> found = cardAccountRepository.findByCardToken("vault:v1:test-token-1");

        assertThat(found).isPresent();
        assertThat(found.get().getCardToken()).isEqualTo("vault:v1:test-token-1");
    }

    @Test
    void shouldReturnEmptyForUnknownToken() {
        Optional<CardAccount> found = cardAccountRepository.findByCardToken("vault:v1:unknown");
        assertThat(found).isEmpty();
    }

    private CardAccount buildAccount(String token) {
        CardAccount account = new CardAccount();
        account.setCardToken(token);
        account.setStatus(CardAccountStatus.ACTIVE);
        account.setCreditLimit(new BigDecimal("1000.00"));
        account.setAvailableCredit(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        return account;
    }
}
