package com.creditcard.frauddetection.repository;

import com.creditcard.frauddetection.domain.FraudAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FraudAlertRepository extends JpaRepository<FraudAlert, UUID> {
}
