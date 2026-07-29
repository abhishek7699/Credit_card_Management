package com.creditcard.settlement.kafka;

import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.kafka.event.TransactionAuthorizedEvent;
import com.creditcard.settlement.repository.ProcessedEventRepository;
import com.creditcard.settlement.repository.SettlementTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TransactionAuthorizedListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionAuthorizedListener.class);

    private final ProcessedEventRepository processedEventRepository;
    private final SettlementTransactionRepository settlementTransactionRepository;

    public TransactionAuthorizedListener(ProcessedEventRepository processedEventRepository,
                                         SettlementTransactionRepository settlementTransactionRepository) {
        this.processedEventRepository = processedEventRepository;
        this.settlementTransactionRepository = settlementTransactionRepository;
    }

    @KafkaListener(
            topics = "transaction.authorized",
            containerFactory = "txAuthorizedContainerFactory",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onTransactionAuthorized(TransactionAuthorizedEvent event, Acknowledgment ack) {
        try {
            if (event == null || event.getPayload() == null) {
                log.warn("Received null TransactionAuthorizedEvent or payload — discarding");
                return;
            }
            if (event.getEventId() == null) {
                log.warn("TransactionAuthorizedEvent missing eventId — discarding");
                return;
            }
            TransactionAuthorizedEvent.Payload payload = event.getPayload();
            if (payload.getTransactionId() == null) {
                log.warn("TransactionAuthorizedEvent payload missing transactionId — discarding");
                return;
            }
            if (payload.getMerchantId() == null) {
                log.warn("TransactionAuthorizedEvent payload missing merchantId — discarding");
                return;
            }

            String eventKey = "settlement:TransactionAuthorized:" + event.getEventId();
            int inserted = processedEventRepository.insertIfAbsent(eventKey);
            if (inserted == 0) {
                log.debug("Duplicate event {}, skipping", event.getEventId());
                return;
            }

            SettlementTransaction tx = new SettlementTransaction();
            tx.setTransactionId(payload.getTransactionId());
            tx.setMerchantId(payload.getMerchantId());
            tx.setCardToken(payload.getCardToken());
            tx.setMaskedCardNumber(payload.getMaskedCardNumber());
            tx.setAmount(payload.getAmount());
            tx.setCurrencyCode(payload.getCurrencyCode() != null ? payload.getCurrencyCode() : "USD");
            tx.setAuthorizedAt(payload.getAuthorizedAt());
            tx.setStatus("AUTHORIZED");

            settlementTransactionRepository.save(tx);
        } finally {
            ack.acknowledge();
        }
    }
}
