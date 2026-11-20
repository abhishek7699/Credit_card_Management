package com.creditcard.mastercard.developers;

import com.mastercard.developer.utils.AuthenticationUtils;
import java.security.PrivateKey;
import java.util.Map;

/**
 * Settings for the Mastercard Developers client. The consumer key and signing key come from environment
 * variables only. {@link #toString()} never reveals either.
 */
public final class MastercardDevelopersConfig {

    public static final String SANDBOX_BASE_URL = "https://sandbox.api.move.mastercard.com/common";
    /** The sandbox accepts any Partner-Id of valid length (32-40). This is the docs' example value. */
    public static final String DEFAULT_PARTNER_ID = "ptnr_1lzs0zD6uWo7k-OdUXjLW_pFgX2";

    static final String ENV_CONSUMER_KEY = "MASTERCARD_DEV_CONSUMER_KEY";
    static final String ENV_KEY_PATH = "MASTERCARD_DEV_KEY_PATH";
    static final String ENV_KEY_ALIAS = "MASTERCARD_DEV_KEY_ALIAS";
    static final String ENV_KEYSTORE_PASSWORD = "MASTERCARD_DEV_KEYSTORE_PASSWORD";

    private final String baseUrl;
    private final String consumerKey;
    private final PrivateKey signingKey;
    private final String partnerId;

    public MastercardDevelopersConfig(String baseUrl, String consumerKey, PrivateKey signingKey, String partnerId) {
        require(baseUrl != null && !baseUrl.isBlank(), "base URL is required");
        require(consumerKey != null && !consumerKey.isBlank(), "consumer key is required");
        require(signingKey != null, "signing key is required");
        require(partnerId != null && partnerId.length() >= 32 && partnerId.length() <= 40,
                "partner id must be 32-40 characters");
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.consumerKey = consumerKey;
        this.signingKey = signingKey;
        this.partnerId = partnerId;
    }

    /** Builds the sandbox config from the four MASTERCARD_DEV_* variables; names missing ones, never values. */
    public static MastercardDevelopersConfig fromEnvironment(Map<String, String> env) throws Exception {
        for (String name : new String[] {ENV_CONSUMER_KEY, ENV_KEY_PATH, ENV_KEY_ALIAS, ENV_KEYSTORE_PASSWORD}) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Missing required environment variable: " + name);
            }
        }
        PrivateKey key = AuthenticationUtils.loadSigningKey(
                env.get(ENV_KEY_PATH), env.get(ENV_KEY_ALIAS), env.get(ENV_KEYSTORE_PASSWORD));
        String partnerId = env.getOrDefault("MASTERCARD_DEV_PARTNER_ID", DEFAULT_PARTNER_ID);
        return new MastercardDevelopersConfig(SANDBOX_BASE_URL, env.get(ENV_CONSUMER_KEY), key, partnerId);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    public String baseUrl() { return baseUrl; }
    public String consumerKey() { return consumerKey; }
    public PrivateKey signingKey() { return signingKey; }
    public String partnerId() { return partnerId; }

    @Override
    public String toString() {
        return "MastercardDevelopersConfig{baseUrl=" + baseUrl + ", consumerKey=****, signingKey=****}";
    }
}
