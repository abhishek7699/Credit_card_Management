package com.creditcard.notification.config;

import com.creditcard.notification.kafka.event.FraudAlertEvent;
import com.creditcard.notification.kafka.event.TransactionStatusEvent;
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

    // Both factories get a MicrometerConsumerListener so
    // kafka_consumer_fetch_manager_records_lag (and the rest of the
    // kafka-clients consumer metrics) show up via /actuator/prometheus for
    // k8s/monitoring's Grafana dashboard -- needed because these are
    // manually-constructed ConsumerFactory beans, which Spring Boot's own
    // Kafka metrics auto-configuration never attaches to on its own.

    @Bean
    public ConsumerFactory<String, TransactionStatusEvent> txStatusConsumerFactory(MeterRegistry meterRegistry) {
        DefaultKafkaConsumerFactory<String, TransactionStatusEvent> factory =
                new DefaultKafkaConsumerFactory<>(consumerProps(TransactionStatusEvent.class));
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionStatusEvent> txStatusContainerFactory(
            ConsumerFactory<String, TransactionStatusEvent> txStatusConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, TransactionStatusEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(txStatusConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    @Bean
    public ConsumerFactory<String, FraudAlertEvent> fraudAlertConsumerFactory(MeterRegistry meterRegistry) {
        DefaultKafkaConsumerFactory<String, FraudAlertEvent> factory =
                new DefaultKafkaConsumerFactory<>(consumerProps(FraudAlertEvent.class));
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, FraudAlertEvent> fraudAlertContainerFactory(
            ConsumerFactory<String, FraudAlertEvent> fraudAlertConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, FraudAlertEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(fraudAlertConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    private Map<String, Object> consumerProps(Class<?> valueType) {
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
        return props;
    }
}
