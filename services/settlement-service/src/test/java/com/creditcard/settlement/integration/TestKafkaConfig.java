package com.creditcard.settlement.integration;

import com.creditcard.settlement.kafka.event.TransactionAuthorizedEvent;
import com.creditcard.settlement.kafka.event.TransactionSettledEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@TestConfiguration
public class TestKafkaConfig {

    @Bean(name = "txAuthorizedKafkaTemplate")
    public KafkaTemplate<String, TransactionAuthorizedEvent> txAuthorizedKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers}") String brokers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        DefaultKafkaProducerFactory<String, TransactionAuthorizedEvent> pf =
                new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    @Bean(name = "settledEventsReceived")
    public BlockingQueue<ConsumerRecord<String, TransactionSettledEvent>> settledEventsReceived() {
        return new LinkedBlockingQueue<>();
    }

    @Bean
    public KafkaMessageListenerContainer<String, TransactionSettledEvent> settledEventListenerContainer(
            @Value("${spring.kafka.bootstrap-servers}") String brokers,
            BlockingQueue<ConsumerRecord<String, TransactionSettledEvent>> settledEventsReceived) {

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-settled-consumer-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.creditcard.*");
        props.put("spring.json.use.type.headers", false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionSettledEvent.class.getName());

        ConsumerFactory<String, TransactionSettledEvent> cf =
                new DefaultKafkaConsumerFactory<>(props);

        ContainerProperties containerProps = new ContainerProperties("transaction.settled");
        containerProps.setMessageListener(
                (MessageListener<String, TransactionSettledEvent>) record ->
                        settledEventsReceived.add(record));

        KafkaMessageListenerContainer<String, TransactionSettledEvent> container =
                new KafkaMessageListenerContainer<>(cf, containerProps);
        container.start();
        return container;
    }
}
