package com.creditcard.authorization.config;

import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import com.creditcard.authorization.kafka.event.TransactionTimedOutEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, TransactionInitiatedEvent> consumerFactory(MeterRegistry meterRegistry) {
        return consumerFactory(meterRegistry, TransactionInitiatedEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionInitiatedEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, TransactionInitiatedEvent> consumerFactory) {
        return containerFactory(consumerFactory);
    }

    /** {@code transaction.timed_out} (013): its own factory, because the default value type differs. */
    @Bean
    public ConsumerFactory<String, TransactionTimedOutEvent> timedOutConsumerFactory(MeterRegistry meterRegistry) {
        return consumerFactory(meterRegistry, TransactionTimedOutEvent.class);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionTimedOutEvent> timedOutContainerFactory(
            ConsumerFactory<String, TransactionTimedOutEvent> timedOutConsumerFactory) {
        return containerFactory(timedOutConsumerFactory);
    }

    private <T> ConsumerFactory<String, T> consumerFactory(MeterRegistry meterRegistry, Class<T> valueType) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, valueType.getName());
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.creditcard.*");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        DefaultKafkaConsumerFactory<String, T> factory = new DefaultKafkaConsumerFactory<>(props);
        // Exposes kafka_consumer_fetch_manager_records_lag (and the rest of the
        // kafka-clients consumer metrics) via Micrometer -> /actuator/prometheus,
        // for k8s/monitoring's Grafana dashboard. Needed because this is a
        // manually-constructed ConsumerFactory -- Spring Boot's own Kafka metrics
        // auto-configuration only attaches to its own auto-configured bean.
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    private <T> ConcurrentKafkaListenerContainerFactory<String, T> containerFactory(ConsumerFactory<String, T> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
