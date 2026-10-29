package com.creditcard.booking.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** Just the giving-up recoverer invoked after bounded retries are exhausted
 *  (research.md §3a) — wired into DefaultErrorHandler in txStatusContainerFactory. */
class KafkaConsumerConfigTest {

    @Test
    void recordGivingUp_logsWithoutThrowing() {
        KafkaConsumerConfig config = new KafkaConsumerConfig();
        ConsumerRecord<String, String> record = new ConsumerRecord<>("transaction.status.updated", 0, 0, "key", "value");

        config.recordGivingUp(record, new RuntimeException("boom"));
    }
}
