package com.creditcard.settlement.capture;

import com.creditcard.mastercard.GatewayOutcome;
import com.creditcard.mastercard.GatewayResult;
import com.creditcard.mastercard.MastercardGatewayClient;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mastercard CAPTURE for settlement. The capture transaction ID is derived from ours (transactionId + ":CAPTURE"),
 * so a retried capture reuses it and never creates a second capture.
 *
 * <p>An UNKNOWN capture is resolved by a status lookup before deciding. If the lookup is still unknown, the result
 * is UNKNOWN and the transaction is left for the next run.
 */
public class MastercardCaptureGateway implements CaptureGateway {

    private final MastercardGatewayClient client;

    public MastercardCaptureGateway(MastercardGatewayClient client) {
        this.client = client;
    }

    @Override
    public CaptureResult capture(UUID transactionId, BigDecimal amount, String currencyCode) {
        GatewayResult result = client.capture(transactionId, amount, currencyCode);
        if (result.outcome() == GatewayOutcome.APPROVED) {
            return CaptureResult.CAPTURED;
        }
        if (result.outcome() == GatewayOutcome.DECLINED) {
            return CaptureResult.FAILED;
        }
        return lookup(transactionId);
    }

    private CaptureResult lookup(UUID transactionId) {
        GatewayResult status = client.retrieveCapture(transactionId);
        if (status.outcome() == GatewayOutcome.APPROVED) {
            return CaptureResult.CAPTURED;
        }
        if (status.outcome() == GatewayOutcome.DECLINED) {
            return CaptureResult.FAILED;
        }
        return CaptureResult.UNKNOWN;
    }
}
