package com.creditcard.authorization.processor;

import com.creditcard.authorization.domain.CardAccount;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Input to {@link PaymentProcessor#authorize}. Phase 1 covers the simulator path only.
 *
 * <p>{@code lockedAccount} is the card row, locked by {@code AuthorizationService} before the timeout
 * marker check (013). It is null when no card row exists. {@link #toString()} never includes the card token.
 */
public final class AuthorizeRequest {

    private final UUID transactionId;
    private final BigDecimal amount;
    private final String currencyCode;
    private final String merchantId;
    private final CardAccount lockedAccount;

    public AuthorizeRequest(UUID transactionId, BigDecimal amount, String currencyCode,
                            String merchantId, CardAccount lockedAccount) {
        this.transactionId = transactionId;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.merchantId = merchantId;
        this.lockedAccount = lockedAccount;
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

    public CardAccount getLockedAccount() {
        return lockedAccount;
    }

    @Override
    public String toString() {
        return "AuthorizeRequest{transactionId=" + transactionId + ", amount=" + amount
                + ", currencyCode=" + currencyCode + ", merchantId=" + merchantId + "}";
    }
}
