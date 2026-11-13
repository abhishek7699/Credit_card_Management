package com.creditcard.settlement.capture;

import java.math.BigDecimal;
import java.util.UUID;

/** Captures an authorized transaction before it is settled. The simulator path captures nothing. */
public interface CaptureGateway {

    CaptureResult capture(UUID transactionId, BigDecimal amount, String currencyCode);
}
