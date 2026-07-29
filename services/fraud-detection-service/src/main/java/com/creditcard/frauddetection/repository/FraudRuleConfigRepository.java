package com.creditcard.frauddetection.repository;

import com.creditcard.frauddetection.domain.FraudRuleConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudRuleConfigRepository extends JpaRepository<FraudRuleConfig, String> {
}
