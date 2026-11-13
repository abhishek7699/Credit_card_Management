package com.creditcard.settlement.capture;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedCaptureGatewayTest {

    @Test
    void everyCaptureSucceeds() {
        assertThat(new SimulatedCaptureGateway().capture(UUID.randomUUID(), BigDecimal.ONE, "USD"))
                .isEqualTo(CaptureResult.CAPTURED);
    }
}
