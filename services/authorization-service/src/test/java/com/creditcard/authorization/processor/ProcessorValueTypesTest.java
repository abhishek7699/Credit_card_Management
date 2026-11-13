package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.CardAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Covers the value types and the processor config bean (spec 014 T006, T007, T018). */
class ProcessorValueTypesTest {

    @Test
    void decisionFactoriesCarryTheirOutcomeAndCodes() {
        assertThat(ProcessorDecision.approved("ref-1").getReference()).isEqualTo("ref-1");
        assertThat(ProcessorDecision.approved("ref-1").getOutcome()).isEqualTo(ProcessorOutcome.APPROVED);
        assertThat(ProcessorDecision.declined("INSUFFICIENT_CREDIT").getReasonCode()).isEqualTo("INSUFFICIENT_CREDIT");
        assertThat(ProcessorDecision.unknown().getOutcome()).isEqualTo(ProcessorOutcome.UNKNOWN);
        assertThat(ProcessorDecision.released().getOutcome()).isEqualTo(ProcessorOutcome.RELEASED);
        assertThat(ProcessorDecision.noAuthorization().getOutcome()).isEqualTo(ProcessorOutcome.NO_AUTHORIZATION);
        assertThat(ProcessorDecision.noOp().getOutcome()).isEqualTo(ProcessorOutcome.NO_OP);
        assertThat(ProcessorDecision.captureRejected("X").getReasonCode()).isEqualTo("X");
    }

    @Test
    void decisionToStringHasOnlyOutcomeAndReason() {
        assertThat(ProcessorDecision.declined("CARD_NOT_ACTIVE").toString())
                .isEqualTo("ProcessorDecision{outcome=DECLINED, reasonCode=CARD_NOT_ACTIVE}");
    }

    @Test
    void requestExposesItsFieldsAndToStringOmitsTheCardToken() {
        UUID txId = UUID.randomUUID();
        CardAccount locked = new CardAccount();
        locked.setCardToken("vault:v1:secret-token");
        AuthorizeRequest request = new AuthorizeRequest(txId, new BigDecimal("12.50"), "USD", "merchant-1", locked);

        assertThat(request.getTransactionId()).isEqualTo(txId);
        assertThat(request.getAmount()).isEqualByComparingTo("12.50");
        assertThat(request.getCurrencyCode()).isEqualTo("USD");
        assertThat(request.getMerchantId()).isEqualTo("merchant-1");
        assertThat(request.getLockedAccount()).isSameAs(locked);
        assertThat(request.toString()).doesNotContain("vault:v1:secret-token");
    }

    @Test
    void configBeanIsTheSimulator() {
        PaymentProcessor bean = new ProcessorConfig().paymentProcessor(null, null);
        assertThat(bean).isInstanceOf(SimulatedProcessor.class);
    }
}
