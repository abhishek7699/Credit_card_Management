package com.creditcard.mastercard;

/**
 * Outcome of one gateway call plus the gateway's code, when it sent one. Safe to log: it carries no
 * card data, session ID, or credential.
 */
public record GatewayResult(GatewayOutcome outcome, String gatewayCode) {
}
