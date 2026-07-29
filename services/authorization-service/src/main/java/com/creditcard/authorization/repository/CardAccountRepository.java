package com.creditcard.authorization.repository;

import com.creditcard.authorization.domain.CardAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CardAccountRepository extends JpaRepository<CardAccount, UUID> {

    Optional<CardAccount> findByCardToken(String cardToken);
}
