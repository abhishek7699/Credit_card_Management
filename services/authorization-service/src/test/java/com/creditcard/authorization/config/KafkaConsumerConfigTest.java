package com.creditcard.authorization.config;

import com.creditcard.authorization.kafka.event.TransactionInitiatedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerConfigTest {

    private KafkaConsumerConfig config;
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        config = new KafkaConsumerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "groupId", "test-group");
    }

    @Test
    void shouldConfigureManualImmediateAckMode() {
        ConcurrentKafkaListenerContainerFactory<String, TransactionInitiatedEvent> factory =
                config.kafkaListenerContainerFactory(config.consumerFactory(meterRegistry));
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    }

    @Test
    void shouldUseStringDeserializerForKey() {
        ConsumerFactory<String, TransactionInitiatedEvent> factory = config.consumerFactory(meterRegistry);
        Object keyDeserializer = factory.getConfigurationProperties()
                .get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
        assertThat(keyDeserializer).isEqualTo(StringDeserializer.class);
    }

    @Test
    void shouldDisableAutoCommit() {
        ConsumerFactory<String, TransactionInitiatedEvent> factory = config.consumerFactory(meterRegistry);
        Object autoCommit = factory.getConfigurationProperties()
                .get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG);
        assertThat(autoCommit).isEqualTo(false);
    }

    @Test
    void shouldUseReadCommittedIsolation() {
        ConsumerFactory<String, TransactionInitiatedEvent> factory = config.consumerFactory(meterRegistry);
        Object isolationLevel = factory.getConfigurationProperties()
                .get(ConsumerConfig.ISOLATION_LEVEL_CONFIG);
        assertThat(isolationLevel).isEqualTo("read_committed");
    }
}
