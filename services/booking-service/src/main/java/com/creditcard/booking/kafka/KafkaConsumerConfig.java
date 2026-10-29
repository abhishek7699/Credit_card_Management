package com.creditcard.booking.kafka;

import com.creditcard.booking.kafka.event.TransactionStatusEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, TransactionStatusEvent> txStatusConsumerFactory(MeterRegistry meterRegistry) {
        DefaultKafkaConsumerFactory<String, TransactionStatusEvent> factory =
                new DefaultKafkaConsumerFactory<>(consumerProps());
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    /**
     * Unlike other services' consumer containers (which catch every exception inside
     * the listener and always ack), this one lets {@code BookingNotFoundForTransactionException}
     * — and any other processing failure — propagate so the container's error handler
     * retries with bounded backoff before giving up (research.md §3a). The booking row
     * and this event can race: booking-service persists the booking HELD, calls
     * transaction-service, then saves the returned transactionId as three separate
     * steps, not one atomic unit with the event's production on the other side.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionStatusEvent> txStatusContainerFactory(
            ConsumerFactory<String, TransactionStatusEvent> txStatusConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionStatusEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(txStatusConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(this::recordGivingUp, new FixedBackOff(2000L, 5)));
        return factory;
    }

    void recordGivingUp(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record, Exception exception) {
        log.error("Giving up on transaction.status.updated record after retries: key={}, exception={}",
                record.key(), exception.getMessage());
    }

    private Map<String, Object> consumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionStatusEvent.class.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.creditcard.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        return props;
    }
}
