package com.creditcard.mastercard.developers;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** No real credentials: a throwaway keystore is generated with the JDK's keytool, the server is WireMock. */
class DevelopersClientTest {

    private static final String PASSWORD = "changeit";
    private static final String ALIAS = "test-key";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 10, 30, 5);

    @TempDir Path tmp;
    WireMockServer wireMock;
    Map<String, String> env;

    @BeforeEach
    void setUp() throws Exception {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        Path p12 = tmp.resolve("test.p12");
        Process p = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=test", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", p12.toString(), "-storepass", PASSWORD)
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
        env = new HashMap<>();
        env.put("MASTERCARD_DEV_CONSUMER_KEY", "test-consumer-key!test-id");
        env.put("MASTERCARD_DEV_KEY_PATH", p12.toString());
        env.put("MASTERCARD_DEV_KEY_ALIAS", ALIAS);
        env.put("MASTERCARD_DEV_KEYSTORE_PASSWORD", PASSWORD);
    }

    @AfterEach
    void tearDown() { wireMock.stop(); }

    private MastercardDevelopersConfig configForWireMock() throws Exception {
        PrivateKey key = MastercardDevelopersConfig.fromEnvironment(env).signingKey();
        return new MastercardDevelopersConfig("http://localhost:" + wireMock.port() + "/common/",
                env.get("MASTERCARD_DEV_CONSUMER_KEY"), key, MastercardDevelopersConfig.DEFAULT_PARTNER_ID);
    }

    @Test
    void sendsSignedPostWithDocumentedHeadersAndBody() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/common/account-validations"))
                .willReturn(okJson("{\"networkResponseCode\":\"00\"}")));

        AccountValidationClient.Result result = new AccountValidationClient(configForWireMock()).validateSandboxPositive(NOW);

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"00\"");
        LoggedRequest req = wireMock.findAll(postRequestedFor(urlEqualTo("/common/account-validations"))).get(0);
        String auth = req.getHeader("Authorization");
        assertThat(auth).startsWith("OAuth ")
                .contains("oauth_consumer_key=\"test-consumer-key!test-id\"")
                .contains("oauth_body_hash=").contains("oauth_signature=\"").contains("oauth_signature_method=\"RSA-SHA256\"");
        assertThat(req.getHeader("Content-Type")).isEqualTo("application/json");
        assertThat(req.getHeader("Accept")).isEqualTo("application/json");
        assertThat(req.getHeader("Partner-Id")).isEqualTo(MastercardDevelopersConfig.DEFAULT_PARTNER_ID);

        JsonNode body = new ObjectMapper().readTree(req.getBodyAsString());
        assertThat(body.at("/referenceId").asText()).isEqualTo("AVM_261120103005");
        assertThat(body.at("/accountDetails/account/type").asText()).isEqualTo("PAN");
        assertThat(body.at("/accountDetails/account/number").asText()).isEqualTo("5102589999999913");
        assertThat(body.at("/accountDetails/name/lastName").asText()).isEqualTo("Candy-Smith");
        assertThat(body.at("/currency").asText()).isEqualTo("USD");
        assertThat(body.at("/transactionLocalDateTime").asText()).isEqualTo("2026-11-20T10:30:05");
    }

    @Test
    void returnsNon200StatusAndBodyUnchanged() throws Exception {
        wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(401).withBody("{\"Errors\":{}}")));
        AccountValidationClient.Result result = new AccountValidationClient(configForWireMock()).validateSandboxPositive(NOW);
        assertThat(result.status()).isEqualTo(401);
        assertThat(result.body()).isEqualTo("{\"Errors\":{}}");
    }

    @Test
    void configFromEnvironmentUsesSandboxUrlAndMasksSecrets() throws Exception {
        MastercardDevelopersConfig config = MastercardDevelopersConfig.fromEnvironment(env);
        assertThat(config.baseUrl()).isEqualTo("https://sandbox.api.move.mastercard.com/common");
        assertThat(config.toString()).doesNotContain("test-consumer-key").contains("****");
    }

    @Test
    void configHonoursPartnerIdOverride() throws Exception {
        env.put("MASTERCARD_DEV_PARTNER_ID", "p".repeat(32));
        assertThat(MastercardDevelopersConfig.fromEnvironment(env).partnerId()).hasSize(32);
    }

    @Test
    void missingVariableIsNamedButNotItsValue() {
        env.remove("MASTERCARD_DEV_KEY_ALIAS");
        assertThatThrownBy(() -> MastercardDevelopersConfig.fromEnvironment(env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MASTERCARD_DEV_KEY_ALIAS");
        env.put("MASTERCARD_DEV_KEY_ALIAS", " ");
        assertThatThrownBy(() -> MastercardDevelopersConfig.fromEnvironment(env))
                .hasMessageContaining("MASTERCARD_DEV_KEY_ALIAS");
    }

    @Test
    void configRejectsBadArguments() throws Exception {
        PrivateKey key = MastercardDevelopersConfig.fromEnvironment(env).signingKey();
        String ok = MastercardDevelopersConfig.DEFAULT_PARTNER_ID;
        assertThatThrownBy(() -> new MastercardDevelopersConfig("", "k", key, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MastercardDevelopersConfig(null, "k", key, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MastercardDevelopersConfig("https://x", " ", key, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MastercardDevelopersConfig("https://x", null, key, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MastercardDevelopersConfig("https://x", "k", null, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MastercardDevelopersConfig("https://x", "k", key, "short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MastercardDevelopersConfig("https://x", "k", key, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MastercardDevelopersConfig("https://x", "k", key, "p".repeat(41))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void smokeRefusesWhenVariablesMissingAndPrintsNoValues() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SandboxSmoke.run(Map.of(), new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("MASTERCARD_DEV_CONSUMER_KEY");
    }

    @Test
    void smokeReportsFailureWhenKeystoreCannotBeRead() {
        env.put("MASTERCARD_DEV_KEYSTORE_PASSWORD", "wrong");
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SandboxSmoke.run(env, new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).startsWith("Smoke failed").doesNotContain("wrong");
    }
}
