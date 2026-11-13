package com.creditcard.mastercard;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MastercardGatewayConfigTest {

    private static MastercardGatewayConfig valid() {
        return new MastercardGatewayConfig("https://test-gateway.mastercard.com", "TESTMERCH", "pw-secret",
                "100", Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Test
    void acceptsAValidConfigAndExposesIt() {
        MastercardGatewayConfig config = valid();
        assertThat(config.getBaseUrl()).isEqualTo("https://test-gateway.mastercard.com");
        assertThat(config.getApiVersion()).isEqualTo("100");
        assertThat(config.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.getMerchantId()).isEqualTo("TESTMERCH");
        assertThat(config.getApiPassword()).isEqualTo("pw-secret");
    }

    @Test
    void toStringMasksTheMerchantIdAndPassword() {
        assertThat(valid().toString()).doesNotContain("TESTMERCH").doesNotContain("pw-secret").contains("****");
    }

    @Test
    void rejectsPlainHttpAndMissingUrl() {
        assertThatThrownBy(() -> new MastercardGatewayConfig("http://insecure", "M", "p", "100",
                Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https://");
        assertThatThrownBy(() -> new MastercardGatewayConfig(null, "M", "p", "100",
                Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https://");
    }

    @Test
    void rejectsBlankMerchantIdPasswordAndVersion() {
        assertThatThrownBy(() -> new MastercardGatewayConfig("https://h", null, "p", "100",
                Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .hasMessageContaining("merchant-id");
        assertThatThrownBy(() -> new MastercardGatewayConfig("https://h", "M", "  ", "100",
                Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .hasMessageContaining("api-password");
        assertThatThrownBy(() -> new MastercardGatewayConfig("https://h", "M", "p", "",
                Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .hasMessageContaining("api-version");
    }

    @Test
    void rejectsNegativeAndMissingTimeouts() {
        assertThatThrownBy(() -> new MastercardGatewayConfig("https://h", "M", "p", "100",
                Duration.ofSeconds(-1), Duration.ofSeconds(1))).hasMessageContaining("connect");
        assertThatThrownBy(() -> new MastercardGatewayConfig("https://h", "M", "p", "100",
                Duration.ofSeconds(1), null)).hasMessageContaining("read");
        assertThatThrownBy(() -> new MastercardGatewayConfig("https://h", "M", "p", "100",
                Duration.ofSeconds(1), Duration.ZERO)).hasMessageContaining("read");
    }

    @Test
    void rejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> new MastercardGatewayConfig("https://h", "M", "p", "100",
                null, Duration.ofSeconds(1))).hasMessageContaining("connect");
        assertThatThrownBy(() -> new MastercardGatewayConfig("https://h", "M", "p", "100",
                Duration.ZERO, Duration.ofSeconds(1))).hasMessageContaining("connect");
        assertThatThrownBy(() -> new MastercardGatewayConfig("https://h", "M", "p", "100",
                Duration.ofSeconds(1), Duration.ofSeconds(-1))).hasMessageContaining("read");
    }
}
