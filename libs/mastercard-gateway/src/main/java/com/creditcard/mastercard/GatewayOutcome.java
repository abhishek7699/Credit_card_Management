package com.creditcard.mastercard;

/**
 * What one Mastercard Gateway call means for us. The only place Mastercard codes are interpreted
 * (contracts/mastercard-gateway.md, response mapping).
 */
public enum GatewayOutcome {
    /** AUTHORIZE or RETRIEVE shows the payment approved. */
    APPROVED,
    /** AUTHORIZE or RETRIEVE shows the payment declined. {@link GatewayResult#gatewayCode()} says why. */
    DECLINED,
    /** Timeout, connection error, 5xx, 404, duplicate identifier, unparseable body, or an unclear result. */
    UNKNOWN,
    /** VOID succeeded. */
    VOIDED,
    /** VOID was refused, for example because the transaction was already captured. */
    VOID_REJECTED
}
