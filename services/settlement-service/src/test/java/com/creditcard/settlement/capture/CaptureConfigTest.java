package com.creditcard.settlement.capture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaptureConfigTest {

    private static CaptureGateway create(String processorName, String merchantId) throws Exception {
        return new CaptureConfig().captureGateway(processorName, "https://localhost:9443", merchantId,
                "fixture-password", "100", 5, 10);
    }

    @Test
    void simulatedCapturesNothingRemotely() throws Exception {
        assertThat(create("simulated", "")).isInstanceOf(SimulatedCaptureGateway.class);
    }

    @Test
    void mastercardSelectsTheGatewayCapture() throws Exception {
        assertThat(create("mastercard", "TESTMERCH")).isInstanceOf(MastercardCaptureGateway.class);
    }

    @Test
    void anUnknownProcessorFailsAtStartup() {
        assertThatThrownBy(() -> create("paypal", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment.processor must be 'simulated' or 'mastercard'");
    }
}
