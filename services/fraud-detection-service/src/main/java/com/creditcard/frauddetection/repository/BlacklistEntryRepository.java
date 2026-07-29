package com.creditcard.frauddetection.repository;

import com.creditcard.frauddetection.domain.BlacklistEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlacklistEntryRepository extends JpaRepository<BlacklistEntry, String> {

    boolean existsByCardToken(String cardToken);
}
