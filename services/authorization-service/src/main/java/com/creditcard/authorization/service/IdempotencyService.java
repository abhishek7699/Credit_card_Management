package com.creditcard.authorization.service;

import com.creditcard.authorization.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {

    private final ProcessedEventRepository processedEventRepository;

    public IdempotencyService(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    public boolean isDuplicate(String idempotencyKey) {
        return processedEventRepository.insertIfAbsent(idempotencyKey) == 0;
    }

    public String buildKey(String eventType, String eventId) {
        return "auth:" + eventType + ":" + eventId;
    }
}
