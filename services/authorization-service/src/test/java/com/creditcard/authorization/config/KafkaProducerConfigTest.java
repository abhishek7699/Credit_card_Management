package com.creditcard.authorization.config;

import com.creditcard.authorization.kafka.event.AuthorizationResultEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProducerConfigTest {

    private KafkaProducerConfig config;

    @BeforeEach
    void setUp() {
        config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
    }

    @Test
    void shouldConfigureAcksAll() {
        ProducerFactory<String, AuthorizationResultEvent> factory = config.producerFactory();
        Object acks = factory.getConfigurationProperties().get(ProducerConfig.ACKS_CONFIG);
        assertThat(acks).isEqualTo("all");
    }

    @Test
    void shouldConfigureIdempotentProducer() {
        ProducerFactory<String, AuthorizationResultEvent> factory = config.producerFactory();
        Object idempotent = factory.getConfigurationProperties()
                .get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
        assertThat(idempotent).isEqualTo(true);
    }

    @Test
    void shouldCreateKafkaTemplate() {
        KafkaTemplate<String, AuthorizationResultEvent> template = config.kafkaTemplate();
        assertThat(template).isNotNull();
    }

    @Test
    void shouldConfigureRetries() {
        ProducerFactory<String, AuthorizationResultEvent> factory = config.producerFactory();
        Object retries = factory.getConfigurationProperties().get(ProducerConfig.RETRIES_CONFIG);
        assertThat(retries).isEqualTo(3);
    }
}
