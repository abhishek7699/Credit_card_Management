package com.creditcard.authorization.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
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
        ProducerFactory<String, Object> factory = config.producerFactory();
        assertThat(factory.getConfigurationProperties().get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
    }

    @Test
    void shouldConfigureIdempotentProducer() {
        ProducerFactory<String, Object> factory = config.producerFactory();
        assertThat(factory.getConfigurationProperties().get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
    }

    @Test
    void shouldConfigureRetries() {
        ProducerFactory<String, Object> factory = config.producerFactory();
        assertThat(factory.getConfigurationProperties().get(ProducerConfig.RETRIES_CONFIG)).isEqualTo(3);
    }

    @Test
    void shouldNotAddTypeInfoHeaders() {
        ProducerFactory<String, Object> factory = config.producerFactory();
        assertThat(factory.getConfigurationProperties().get(JsonSerializer.ADD_TYPE_INFO_HEADERS)).isEqualTo(false);
    }

    @Test
    void shouldCreateKafkaTemplate() {
        KafkaTemplate<String, Object> template = config.kafkaTemplate();
        assertThat(template).isNotNull();
    }
}
