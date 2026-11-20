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

    private static AccountValidationClient.Request request(String refId) {
        return AccountValidationClient.Request.fromCard(refId, "5102589999999913", "10/50", "6173", "Jane Michelle Candy-Smith");
    }

    @Test
    void validateApprovesWhenIssuerApprovesAndCvcMatches() throws Exception {
        wireMock.stubFor(post(anyUrl()).willReturn(okJson("""
                {"referenceId":"AVM_1","networkResponseCode":"00",
                 "cvcValidationResults":{"status":"MATCHED"},"nameValidationResults":{"status":"PARTIAL_MATCHED"}}""")));
        AccountValidationClient.Validation v = new AccountValidationClient(configForWireMock()).validate(request("AVM_1"), NOW);
        assertThat(v.approved()).isTrue();
        assertThat(v.referenceId()).isEqualTo("AVM_1");
        assertThat(v.networkResponseCode()).isEqualTo("00");
        JsonNode sent = new ObjectMapper().readTree(
                wireMock.findAll(postRequestedFor(anyUrl())).get(0).getBodyAsString());
        assertThat(sent.at("/accountDetails/account/expiry").asText()).isEqualTo("2050-10");
        assertThat(sent.at("/accountDetails/account/cvc").asText()).isEqualTo("6173");
        assertThat(sent.at("/accountDetails/name/firstName").asText()).isEqualTo("Jane");
        assertThat(sent.at("/accountDetails/name/lastName").asText()).isEqualTo("Candy-Smith");
    }

    @Test
    void validateDoesNotApproveWhenCvcOrNameOrIssuerSaysNo() throws Exception {
        AccountValidationClient client = new AccountValidationClient(configForWireMock());
        String[] bodies = {
                "{\"networkResponseCode\":\"05\",\"cvcValidationResults\":{\"status\":\"MATCHED\"}}",
                "{\"networkResponseCode\":\"00\",\"cvcValidationResults\":{\"status\":\"NOT_MATCHED\"}}",
                "{\"networkResponseCode\":\"00\",\"cvcValidationResults\":{\"status\":\"MATCHED\"},\"nameValidationResults\":{\"status\":\"NOT_MATCHED\"}}",
                "{\"networkResponseCode\":\"00\"}"};
        for (String body : bodies) {
            wireMock.stubFor(post(anyUrl()).willReturn(okJson(body)));
            assertThat(client.validate(request("AVM_2"), NOW).approved()).as(body).isFalse();
        }
    }

    @Test
    void validateTreatsHttp400AsNotApproved() throws Exception {
        wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(400).withBody("{\"Errors\":{}}")));
        AccountValidationClient.Validation v = new AccountValidationClient(configForWireMock()).validate(request("X_1"), NOW);
        assertThat(v.approved()).isFalse();
        assertThat(v.networkResponseCode()).isNull();
    }

    @Test
    void validateFailsClosedOnAuthErrorServerErrorAndGarbage() throws Exception {
        AccountValidationClient client = new AccountValidationClient(configForWireMock());
        for (int status : new int[] {401, 500, 503}) {
            wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(status).withBody("secret-ish body")));
            assertThatThrownBy(() -> client.validate(request("AVM_3"), NOW))
                    .isInstanceOf(AccountValidationUnavailableException.class)
                    .hasMessage("Account Validation returned HTTP " + status);
        }
        wireMock.stubFor(post(anyUrl()).willReturn(okJson("not json")));
        assertThatThrownBy(() -> client.validate(request("AVM_3"), NOW))
                .isInstanceOf(AccountValidationUnavailableException.class).hasMessageContaining("unreadable");
    }

    @Test
    void validateFailsClosedOnTimeoutAndConnectionError() throws Exception {
        MastercardDevelopersConfig config = configForWireMock();
        wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withFixedDelay(1500).withStatus(200)));
        assertThatThrownBy(() -> new AccountValidationClient(config, java.time.Duration.ofMillis(200))
                .validate(request("AVM_4"), NOW))
                .isInstanceOf(AccountValidationUnavailableException.class)
                .hasMessageContaining("HttpTimeoutException");
        wireMock.stop();
        assertThatThrownBy(() -> new AccountValidationClient(config).validate(request("AVM_4"), NOW))
                .isInstanceOf(AccountValidationUnavailableException.class);
    }

    @Test
    void validateRestoresInterruptFlag() throws Exception {
        wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withFixedDelay(1000).withStatus(200)));
        AccountValidationClient client = new AccountValidationClient(configForWireMock());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.validate(request("AVM_5"), NOW))
                    .isInstanceOf(AccountValidationUnavailableException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void requestMasksCardNumberAndCvcAndRejectsBadExpiry() {
        String text = request("AVM_6").toString();
        assertThat(text).doesNotContain("5102589999999913").doesNotContain("6173").contains("AVM_6");
        assertThatThrownBy(() -> AccountValidationClient.Request.fromCard("AVM_6", "5102589999999913", "13/30", "1", "A B"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountValidationClient.Request.fromCard("AVM_6", "5102589999999913", null, "1", "A B"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cardholderNameSplitHandlesOneWordAndBlank() {
        assertThat(CardholderName.split("Madonna")).containsExactly("#NOFIRSTNAME", "Madonna");
        assertThat(CardholderName.split("  Jane   Doe ")).containsExactly("Jane", "Doe");
        assertThat(CardholderName.split("  ")).containsExactly("#NOFIRSTNAME", "#NOLASTNAME");
        assertThat(CardholderName.split(null)).containsExactly("#NOFIRSTNAME", "#NOLASTNAME");
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
