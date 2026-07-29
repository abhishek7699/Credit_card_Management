package com.creditcard.authorization.repository;

import com.creditcard.authorization.domain.AuthorizationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuthorizationRecordRepository extends JpaRepository<AuthorizationRecord, UUID> {

    List<AuthorizationRecord> findByTransactionId(UUID transactionId);
}
