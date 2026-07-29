package com.creditcard.settlement.batch.processor;

import com.creditcard.settlement.domain.SettlementTransaction;
import com.creditcard.settlement.exception.AlreadySettledException;
import com.creditcard.settlement.model.SettledItem;
import com.creditcard.settlement.service.InterchangeFeeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementItemProcessorTest {

    private SettlementItemProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SettlementItemProcessor(new InterchangeFeeService(new BigDecimal("0.02")));
    }

    @Test
    void process_settledTransaction_throwsAlreadySettledException() {
        SettlementTransaction tx = buildTx("SETTLED", "100.00");

        assertThatThrownBy(() -> processor.process(tx))
                .isInstanceOf(AlreadySettledException.class)
                .hasMessageContaining(tx.getTransactionId().toString());
    }

    @Test
    void process_authorizedTransaction_returnsSettledItemWithCorrectFee() throws Exception {
        SettlementTransaction tx = buildTx("AUTHORIZED", "100.00");

        SettledItem result = processor.process(tx);

        assertThat(result.getTransaction()).isSameAs(tx);
        assertThat(result.getGrossAmount()).isEqualByComparingTo("100.00");
        assertThat(result.getInterchangeFee()).isEqualByComparingTo("2.00");
        assertThat(result.getNetAmount()).isEqualByComparingTo("98.00");
    }

    @Test
    void process_roundingHalfUp_feeAndNetCorrect() throws Exception {
        SettlementTransaction tx = buildTx("AUTHORIZED", "150.75");

        SettledItem result = processor.process(tx);

        assertThat(result.getInterchangeFee()).isEqualByComparingTo("3.02");
        assertThat(result.getNetAmount()).isEqualByComparingTo("147.73");
    }

    @Test
    void process_largeAmount_correct() throws Exception {
        SettlementTransaction tx = buildTx("AUTHORIZED", "10000.00");

        SettledItem result = processor.process(tx);

        assertThat(result.getInterchangeFee()).isEqualByComparingTo("200.00");
        assertThat(result.getNetAmount()).isEqualByComparingTo("9800.00");
    }

    private SettlementTransaction buildTx(String status, String amount) {
        SettlementTransaction tx = new SettlementTransaction();
        tx.setTransactionId(UUID.randomUUID());
        tx.setMerchantId("merchant-1");
        tx.setCardToken("tok-abc");
        tx.setMaskedCardNumber("4111-****-****-1111");
        tx.setAmount(new BigDecimal(amount));
        tx.setCurrencyCode("USD");
        tx.setStatus(status);
        return tx;
    }
}
