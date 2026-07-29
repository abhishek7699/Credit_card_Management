package com.creditcard.settlement.batch.writer;

import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.kafka.event.TransactionSettledEvent;
import com.creditcard.settlement.model.SettledItem;
import com.creditcard.settlement.repository.MerchantSettlementRepository;
import com.creditcard.settlement.repository.SettlementTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

public class SettlementItemWriter implements ItemWriter<SettledItem> {

    private static final Logger log = LoggerFactory.getLogger(SettlementItemWriter.class);
    private static final String SETTLED_TOPIC = "transaction.settled";

    private final String batchId;
    private final SettlementTransactionRepository txRepository;
    private final MerchantSettlementRepository merchantSettlementRepository;
    private final KafkaTemplate<String, TransactionSettledEvent> kafkaTemplate;

    public SettlementItemWriter(String batchId,
                                SettlementTransactionRepository txRepository,
                                MerchantSettlementRepository merchantSettlementRepository,
                                KafkaTemplate<String, TransactionSettledEvent> kafkaTemplate) {
        this.batchId = batchId;
        this.txRepository = txRepository;
        this.merchantSettlementRepository = merchantSettlementRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void write(Chunk<? extends SettledItem> chunk) {
        Instant settledAt = Instant.now();
        LocalDate settlementDate = LocalDate.now(ZoneOffset.UTC);
        Map<String, MerchantAccumulator> accumulators = new LinkedHashMap<>();

        for (SettledItem item : chunk.getItems()) {
            SettlementTransaction tx = item.getTransaction();
            tx.setStatus("SETTLED");
            tx.setSettlementFee(item.getInterchangeFee());
            tx.setNetAmount(item.getNetAmount());
            tx.setSettledAt(settledAt);
            tx.setBatchId(batchId);
            txRepository.save(tx);

            String merchantId = tx.getMerchantId();
            MerchantAccumulator acc = accumulators.computeIfAbsent(merchantId, k -> new MerchantAccumulator());
            acc.count++;
            acc.gross = acc.gross.add(item.getGrossAmount());
            acc.fee = acc.fee.add(item.getInterchangeFee());
            acc.net = acc.net.add(item.getNetAmount());
        }

        for (Map.Entry<String, MerchantAccumulator> entry : accumulators.entrySet()) {
            merchantSettlementRepository.upsertMerchantSettlement(
                    batchId, entry.getKey(), entry.getValue().count,
                    entry.getValue().gross, entry.getValue().fee, entry.getValue().net, settlementDate);
        }

        for (SettledItem item : chunk.getItems()) {
            try {
                TransactionSettledEvent event = buildEvent(item, settlementDate, settledAt);
                kafkaTemplate.send(SETTLED_TOPIC,
                        item.getTransaction().getTransactionId().toString(), event);
            } catch (Exception e) {
                log.warn("Failed to publish TransactionSettledEvent for tx {}",
                        item.getTransaction().getTransactionId(), e);
            }
        }
    }

    private TransactionSettledEvent buildEvent(SettledItem item, LocalDate settlementDate, Instant settledAt) {
        TransactionSettledEvent event = new TransactionSettledEvent();
        TransactionSettledEvent.Payload payload = new TransactionSettledEvent.Payload();
        payload.setTransactionId(item.getTransaction().getTransactionId());
        payload.setMerchantId(item.getTransaction().getMerchantId());
        payload.setBatchId(batchId);
        payload.setGrossAmount(item.getGrossAmount());
        payload.setInterchangeFee(item.getInterchangeFee());
        payload.setNetAmount(item.getNetAmount());
        payload.setCurrencyCode(item.getTransaction().getCurrencyCode());
        payload.setSettlementDate(settlementDate);
        payload.setSettledAt(settledAt);
        event.setPayload(payload);
        return event;
    }

    private static class MerchantAccumulator {
        int count = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal fee = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
    }
}
