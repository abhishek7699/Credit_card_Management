package com.creditcard.authorization.processor;

import com.creditcard.authorization.repository.AuthorizationRecordRepository;
import com.creditcard.authorization.repository.CardAccountRepository;
import com.creditcard.authorization.repository.TimeoutMarkerRepository;
import com.creditcard.mastercard.TlsFixture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Spec 014 phase 2 (T029): the processor is chosen by payment.processor, the simulator by default. */
class ProcessorConfigTest {

    private final CardAccountRepository cards = mock(CardAccountRepository.class);
    private final AuthorizationRecordRepository records = mock(AuthorizationRecordRepository.class);
    private final TimeoutMarkerRepository markers = mock(TimeoutMarkerRepository.class);

    private PaymentProcessor create(String processorName, String merchantId) throws Exception {
        return new ProcessorConfig().paymentProcessor(processorName, cards, records, markers,
                "https://localhost:9443", merchantId, "fixture-password", "100", 5, 10);
    }

    @Test
    void simulatedIsTheSimulator() throws Exception {
        assertThat(create("simulated", "")).isInstanceOf(SimulatedProcessor.class);
    }

    @Test
    void mastercardSelectsTheGatewayProcessorWithoutTouchingTheSimulator() throws Exception {
        assertThat(create("mastercard", "TESTMERCH")).isInstanceOf(MastercardGatewayProcessor.class);
    }

    @Test
    void anUnknownProcessorFailsAtStartup() {
        assertThatThrownBy(() -> create("paypal", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment.processor must be 'simulated' or 'mastercard'");
    }

    @Test
    void mastercardWithoutAMerchantIdFailsAtStartup() {
        assertThatThrownBy(() -> create("mastercard", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("merchant-id");
    }
}
