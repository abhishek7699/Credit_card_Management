package com.creditcard.frauddetection.rules;

import com.creditcard.frauddetection.kafka.event.TransactionInitiatedEvent;

import java.util.Optional;

public interface FraudRule {

    Optional<String> evaluate(TransactionInitiatedEvent event);
}
