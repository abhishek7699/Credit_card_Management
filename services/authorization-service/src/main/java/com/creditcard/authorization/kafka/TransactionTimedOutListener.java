package com.creditcard.authorization.kafka;

import com.creditcard.authorization.kafka.event.TransactionTimedOutEvent;
import com.creditcard.authorization.service.CreditReleaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code transaction.timed_out} (013) and releases the credit held for the transaction.
 *
 * <p>The offset is committed only after the release commits. If the release fails, nothing is
 * acknowledged, so the event is redelivered and the release runs again.
 */
@Component
public class TransactionTimedOutListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionTimedOutListener.class);

    private final CreditReleaseService creditReleaseService;

    public TransactionTimedOutListener(CreditReleaseService creditReleaseService) {
        this.creditReleaseService = creditReleaseService;
    }

    @KafkaListener(
        topics = "transaction.timed_out",
        groupId = "${spring.kafka.consumer.group-id}",
        containerFactory = "timedOutContainerFactory"
    )
    public void onTransactionTimedOut(TransactionTimedOutEvent event, Acknowledgment ack) {
        if (event == null || event.getEventId() == null || event.getPayload() == null) {
            log.warn("Discarding invalid transaction.timed_out record");
            ack.acknowledge();
            return;
        }
        TransactionTimedOutEvent.Payload payload = event.getPayload();
        creditReleaseService.release(event.getEventId(), payload.getTransactionId(), payload.getCardToken());
        ack.acknowledge();
    }
}
