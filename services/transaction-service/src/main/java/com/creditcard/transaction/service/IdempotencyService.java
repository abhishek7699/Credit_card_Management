package com.creditcard.transaction.service;

import com.creditcard.transaction.domain.ProcessedEvent;
import com.creditcard.transaction.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    private final ProcessedEventRepository processedEventRepository;

    public IdempotencyService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    public boolean isAlreadyProcessed(String eventKey) {
        return processedEventRepository.existsByEventKey(eventKey);
    }

    @Transactional
    public void recordAndExecute(String eventKey, Runnable businessLogic) {
        int inserted = processedEventRepository.insertIfAbsent(eventKey);
        if (inserted == 0) {
            return;
        }
        businessLogic.run();
    }
}
