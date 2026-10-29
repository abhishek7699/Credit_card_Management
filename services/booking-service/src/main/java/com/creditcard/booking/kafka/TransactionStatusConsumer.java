package com.creditcard.booking.kafka;

import com.creditcard.booking.kafka.event.TransactionStatusEvent;
import com.creditcard.booking.service.BookingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TransactionStatusConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionStatusConsumer.class);

    private final BookingService bookingService;

    public TransactionStatusConsumer(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Deliberately no try/catch around {@code handlePaymentOutcome}: any failure
     * (notably {@link BookingNotFoundForTransactionException}) must propagate to the
     * container's error handler, configured in {@link KafkaConsumerConfig} with bounded
     * retry-with-backoff (research.md §3a) — swallowing it here would make the race it
     * exists to handle indistinguishable from "this event has no booking and never
     * will."
     */
    @KafkaListener(
            topics = "transaction.status.updated",
            containerFactory = "txStatusContainerFactory",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onMessage(ConsumerRecord<String, TransactionStatusEvent> record, Acknowledgment ack) {
        if (record.value() == null) {
            log.warn("Received null TransactionStatusEvent payload — discarding");
            ack.acknowledge();
            return;
        }
        bookingService.handlePaymentOutcome(record.value());
        ack.acknowledge();
    }
}
