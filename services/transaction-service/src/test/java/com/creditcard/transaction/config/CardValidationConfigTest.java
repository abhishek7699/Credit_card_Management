package com.creditcard.transaction.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CardValidationConfigTest {

    @TempDir Path tmp;

    @Test
    void enablingWithoutCredentialsFailsStartupNamingTheVariable() {
        assertThatThrownBy(() -> CardValidationConfig.buildClient(Map.of(), Duration.ofSeconds(6)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MASTERCARD_DEV_CONSUMER_KEY");
    }

    @Test
    void buildsAClientFromTheFourEnvironmentVariables() throws Exception {
        Path p12 = tmp.resolve("t.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=t",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", p12.toString(), "-storepass", "changeit")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();

        assertThat(CardValidationConfig.buildClient(Map.of(
                "MASTERCARD_DEV_CONSUMER_KEY", "ck!id",
                "MASTERCARD_DEV_KEY_PATH", p12.toString(),
                "MASTERCARD_DEV_KEY_ALIAS", "k",
                "MASTERCARD_DEV_KEYSTORE_PASSWORD", "changeit"), Duration.ofSeconds(6))).isNotNull();
    }
}
