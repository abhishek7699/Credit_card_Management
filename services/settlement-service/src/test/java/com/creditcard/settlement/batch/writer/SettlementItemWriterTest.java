package com.creditcard.settlement.batch.writer;

import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.kafka.event.TransactionSettledEvent;
import com.creditcard.settlement.model.SettledItem;
import com.creditcard.settlement.repository.MerchantSettlementRepository;
import com.creditcard.settlement.repository.SettlementTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.item.Chunk;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementItemWriterTest {

    @Mock
    private SettlementTransactionRepository txRepository;

    @Mock
    private MerchantSettlementRepository merchantSettlementRepository;

    @Mock
    private KafkaTemplate<String, TransactionSettledEvent> kafkaTemplate;

    private SettlementItemWriter writer;

    @BeforeEach
    void setUp() {
        writer = new SettlementItemWriter("batch-test", txRepository, merchantSettlementRepository, kafkaTemplate);
    }

    private SettledItem buildItem(String merchantId, String amount) {
        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId(UUID.randomUUID());
        tx.setMerchantId(merchantId);
        tx.setCardToken("tok-test");
        tx.setMaskedCardNumber("4111-****-****-1111");
        tx.setAmount(new BigDecimal(amount));
        tx.setCurrencyCode("USD");
        tx.setStatus("AUTHORIZED");
        tx.setAuthorizedAt(Instant.now());
        BigDecimal gross = new BigDecimal(amount);
        BigDecimal fee = gross.multiply(new BigDecimal("0.02"));
        BigDecimal net = gross.subtract(fee);
        return new SettledItem(tx, gross, fee, net);
    }

    @Test
    void write_singleItem_savesAndPublishes() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Chunk<SettledItem> chunk = new Chunk<>(List.of(buildItem("merchant-A", "100.00")));
        writer.write(chunk);

        verify(txRepository).save(any());
        verify(merchantSettlementRepository).upsertMerchantSettlement(
                eq("batch-test"), eq("merchant-A"), eq(1),
                any(), any(), any(), any());
        verify(kafkaTemplate).send(eq("transaction.settled"), any(), any());
    }

    @Test
    void write_multipleItemsSameMerchant_aggregatesCorrectly() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Chunk<SettledItem> chunk = new Chunk<>(List.of(
                buildItem("merchant-A", "100.00"),
                buildItem("merchant-A", "200.00")
        ));
        writer.write(chunk);

        verify(txRepository, times(2)).save(any());
        verify(merchantSettlementRepository, times(1)).upsertMerchantSettlement(
                eq("batch-test"), eq("merchant-A"), eq(2),
                any(), any(), any(), any());
        verify(kafkaTemplate, times(2)).send(eq("transaction.settled"), any(), any());
    }

    @Test
    void write_kafkaThrows_exceptionIsCaughtAndDoesNotPropagate() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kafkaTemplate.send(any(), any(), any()))
                .thenThrow(new RuntimeException("Kafka unavailable"));

        Chunk<SettledItem> chunk = new Chunk<>(List.of(buildItem("merchant-B", "50.00")));

        assertThatNoException().isThrownBy(() -> writer.write(chunk));

        verify(txRepository).save(any());
        verify(merchantSettlementRepository).upsertMerchantSettlement(any(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void write_emptyChunk_doesNothing() {
        Chunk<SettledItem> chunk = new Chunk<>(List.of());
        writer.write(chunk);

        verifyNoInteractions(txRepository, merchantSettlementRepository, kafkaTemplate);
    }

    @Test
    void write_multiMerchant_createsOneUpsertPerMerchant() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Chunk<SettledItem> chunk = new Chunk<>(List.of(
                buildItem("merchant-X", "100.00"),
                buildItem("merchant-Y", "200.00"),
                buildItem("merchant-X", "50.00")
        ));
        writer.write(chunk);

        verify(merchantSettlementRepository).upsertMerchantSettlement(
                eq("batch-test"), eq("merchant-X"), eq(2), any(), any(), any(), any());
        verify(merchantSettlementRepository).upsertMerchantSettlement(
                eq("batch-test"), eq("merchant-Y"), eq(1), any(), any(), any(), any());
    }
}
