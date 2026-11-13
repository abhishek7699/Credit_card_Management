package com.creditcard.settlement.capture;

import java.math.BigDecimal;
import java.util.UUID;

/** The simulator has no remote capture. Settlement behaves exactly as before: every item is captured. */
public class SimulatedCaptureGateway implements CaptureGateway {

    @Override
    public CaptureResult capture(UUID transactionId, BigDecimal amount, String currencyCode) {
        return CaptureResult.CAPTURED;
    }
}
