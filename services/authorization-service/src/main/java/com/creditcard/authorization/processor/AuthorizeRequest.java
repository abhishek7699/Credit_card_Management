package com.creditcard.authorization.processor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Input to {@link PaymentProcessor#authorize}. Processor-neutral: it carries no card row.
 *
 * <p>{@code paymentToken} is the payment's reference as the service received it: the Vault card token for
 * the simulator, or a payment session ID for Mastercard (phase 3). It is never a card number.
 * {@link #toString()} omits it.
 */
public final class AuthorizeRequest {

    private final UUID transactionId;
    private final BigDecimal amount;
    private final String currencyCode;
    private final String merchantId;
    private final String paymentToken;

    public AuthorizeRequest(UUID transactionId, BigDecimal amount, String currencyCode,
                            String merchantId, String paymentToken) {
        this.transactionId = transactionId;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.merchantId = merchantId;
        this.paymentToken = paymentToken;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getPaymentToken() {
        return paymentToken;
    }

    @Override
    public String toString() {
        return "AuthorizeRequest{transactionId=" + transactionId + ", amount=" + amount
                + ", currencyCode=" + currencyCode + ", merchantId=" + merchantId + "}";
    }
}
