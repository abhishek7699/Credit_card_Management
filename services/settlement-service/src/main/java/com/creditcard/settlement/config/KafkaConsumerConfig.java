package com.creditcard.settlement.config;

import com.creditcard.settlement.kafka.event.TransactionAuthorizedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    private Map<String, Object> consumerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.creditcard.*");
        props.put("spring.json.use.type.headers", false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionAuthorizedEvent.class.getName());
        return props;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorizedEvent> txAuthorizedContainerFactory(
            MeterRegistry meterRegistry) {
        DefaultKafkaConsumerFactory<String, TransactionAuthorizedEvent> cf =
                new DefaultKafkaConsumerFactory<>(consumerProps());
        // Exposes kafka_consumer_fetch_manager_records_lag (and the rest of the
        // kafka-clients consumer metrics) via Micrometer -> /actuator/prometheus,
        // for k8s/monitoring's Grafana dashboard. Needed because this is a
        // manually-constructed ConsumerFactory -- Spring Boot's own Kafka metrics
        // auto-configuration only attaches to its own auto-configured bean.
        cf.addListener(new MicrometerConsumerListener<>(meterRegistry));
        ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorizedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
