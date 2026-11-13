package com.creditcard.authorization.repository;

import com.creditcard.authorization.domain.CardAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CardAccountRepository extends JpaRepository<CardAccount, UUID> {

    Optional<CardAccount> findByCardToken(String cardToken);

    /**
     * Reads the card with a row lock held until the transaction ends (013). Authorization and the timeout
     * release both take this lock first, so they run one after the other for the same card.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CardAccount c WHERE c.cardToken = :cardToken")
    Optional<CardAccount> findByCardTokenForUpdate(@Param("cardToken") String cardToken);
}
