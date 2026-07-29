package com.creditcard.transaction.integration;

import com.creditcard.transaction.domain.TransactionStatus;
import com.creditcard.transaction.event.TransactionInitiatedEvent;
import com.creditcard.transaction.grpc.v1.InitiateTransactionRequest;
import com.creditcard.transaction.grpc.v1.InitiateTransactionResponse;
import com.creditcard.transaction.repository.TransactionRepository;
import com.creditcard.transaction.api.grpc.TransactionGrpcService;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.core.VaultTransitOperations;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 1,
        topics = {"transaction.initiated", "transaction.status.updated"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@ActiveProfiles("test")
class TransactionCreationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockBean
    private VaultTemplate vaultTemplate;

    @Autowired
    private TransactionGrpcService transactionGrpcService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Test
    void shouldCreatePendingTransactionWithVaultTokenAndPublishKafkaEvent() {
        VaultTransitOperations transitOps = mock(VaultTransitOperations.class);
        when(vaultTemplate.opsForTransit()).thenReturn(transitOps);
        when(transitOps.encrypt(anyString(), any(Plaintext.class)))
                .thenReturn(Ciphertext.of("vault:v1:test-ciphertext-abc123"));

        AtomicReference<InitiateTransactionResponse> responseRef = new AtomicReference<>();
        StreamObserver<InitiateTransactionResponse> observer = new StreamObserver<>() {
            @Override public void onNext(InitiateTransactionResponse value) { responseRef.set(value); }
            @Override public void onError(Throwable t) { throw new RuntimeException(t); }
            @Override public void onCompleted() {}
        };

        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setCardExpiry("12/27")
                .setCvv("123")
                .setCardholderName("Jane Doe")
                .setAmount("99.99")
                .setCurrencyCode("USD")
                .build();

        transactionGrpcService.initiateTransaction(request, observer);

        // Verify response
        assertThat(responseRef.get()).isNotNull();
        assertThat(responseRef.get().getTransactionId()).isNotBlank();
        assertThat(responseRef.get().getStatus()).isEqualTo("PENDING");

        // Verify DB row — card_token is vault ciphertext, NOT raw PAN
        UUID txId = UUID.fromString(responseRef.get().getTransactionId());
        var tx = transactionRepository.findById(txId).orElseThrow();
        assertThat(tx.getCardToken()).isEqualTo("vault:v1:test-ciphertext-abc123");
        assertThat(tx.getCardToken()).startsWith("vault:");
        assertThat(tx.getCardToken()).doesNotContain("4111111111111111");
        assertThat(tx.getMaskedCardNumber()).isEqualTo("****-****-****-1111");
        assertThat(tx.getMaskedCardNumber()).matches("\\*{4}-\\*{4}-\\*{4}-\\d{4}");
        assertThat(tx.getStatus()).isEqualTo(TransactionStatus.PENDING);

        // Verify Kafka event on transaction.initiated topic
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-consumer-group", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.creditcard.transaction.event");
        consumerProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, TransactionInitiatedEvent.class.getName());

        var consumerFactory = new DefaultKafkaConsumerFactory<String, TransactionInitiatedEvent>(consumerProps);
        var consumer = consumerFactory.createConsumer();
        embeddedKafkaBroker.consumeFromAnEmbeddedTopic(consumer, "transaction.initiated");

        ConsumerRecord<String, TransactionInitiatedEvent> record =
                KafkaTestUtils.getSingleRecord(consumer, "transaction.initiated");
        consumer.close();

        assertThat(record.key()).isEqualTo(txId.toString());
        TransactionInitiatedEvent event = record.value();
        assertThat(event.getEventType()).isEqualTo("TransactionInitiated");
        assertThat(event.getPayload().getTransactionId()).isEqualTo(txId.toString());
        assertThat(event.getPayload().getCardToken()).startsWith("vault:");
        assertThat(event.getPayload().getMaskedCardNumber()).isEqualTo("****-****-****-1111");
        // Raw PAN must never appear in Kafka event
        assertThat(event.getPayload().getCardToken()).doesNotContain("4111111111111111");
        assertThat(event.getPayload().getMaskedCardNumber()).doesNotContain("4111111111111111");
    }

    @Test
    void shouldNotCreateTransactionWhenVaultIsUnavailable() {
        VaultTransitOperations transitOps = mock(VaultTransitOperations.class);
        when(vaultTemplate.opsForTransit()).thenReturn(transitOps);
        when(transitOps.encrypt(anyString(), any(Plaintext.class)))
                .thenThrow(new RuntimeException("Vault connection refused"));

        long countBefore = transactionRepository.count();

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        StreamObserver<InitiateTransactionResponse> observer = new StreamObserver<>() {
            @Override public void onNext(InitiateTransactionResponse value) {}
            @Override public void onError(Throwable t) { errorRef.set(t); }
            @Override public void onCompleted() {}
        };

        InitiateTransactionRequest request = InitiateTransactionRequest.newBuilder()
                .setCardNumber("4111111111111111")
                .setCardExpiry("12/27")
                .setCvv("123")
                .setCardholderName("Jane Doe")
                .setAmount("50.00")
                .setCurrencyCode("USD")
                .build();

        transactionGrpcService.initiateTransaction(request, observer);

        assertThat(errorRef.get()).isNotNull();
        // No DB row created when Vault fails
        assertThat(transactionRepository.count()).isEqualTo(countBefore);
    }
}
