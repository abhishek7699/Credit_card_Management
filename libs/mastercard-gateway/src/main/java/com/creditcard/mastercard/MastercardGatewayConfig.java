package com.creditcard.mastercard;

import java.time.Duration;

/**
 * Settings for the Mastercard Gateway client. Read from environment variables by the service
 * (never committed). Validated at construction, so a bad value fails at startup, not on the first payment.
 *
 * <p>Constitution Principle IX: HTTPS only. {@link #toString()} masks the merchant ID and password.
 */
public final class MastercardGatewayConfig {

    private final String baseUrl;
    private final String merchantId;
    private final String apiPassword;
    private final String apiVersion;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    public MastercardGatewayConfig(String baseUrl, String merchantId, String apiPassword, String apiVersion,
                                   Duration connectTimeout, Duration readTimeout) {
        require(baseUrl != null && baseUrl.startsWith("https://"), "mastercard.base-url must be an https:// URL");
        require(notBlank(merchantId), "mastercard.merchant-id is required");
        require(notBlank(apiPassword), "mastercard.api-password is required");
        require(notBlank(apiVersion), "mastercard.api-version is required");
        require(connectTimeout != null && !connectTimeout.isNegative() && !connectTimeout.isZero(),
                "mastercard connect timeout must be positive");
        require(readTimeout != null && !readTimeout.isNegative() && !readTimeout.isZero(),
                "mastercard read timeout must be positive");
        this.baseUrl = baseUrl;
        this.merchantId = merchantId;
        this.apiPassword = apiPassword;
        this.apiVersion = apiVersion;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getApiPassword() {
        return apiPassword;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    @Override
    public String toString() {
        return "MastercardGatewayConfig{baseUrl=" + baseUrl + ", merchantId=****, apiPassword=****, apiVersion="
                + apiVersion + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "}";
    }
}
