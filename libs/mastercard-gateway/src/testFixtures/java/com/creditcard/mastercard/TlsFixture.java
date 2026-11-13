package com.creditcard.mastercard;

import com.github.tomakehurst.wiremock.WireMockServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Test-only TLS setup, shared with other modules through the java-test-fixtures plugin. The WireMock server
 * presents a self-signed fixture certificate for localhost. The client trusts only that certificate.
 * Certificate verification stays on, and no trust-all client is used.
 */
public final class TlsFixture {

    public static final String KEYSTORE_PASSWORD = "changeit";

    private TlsFixture() {
    }

    /** An HTTPS WireMock server on dynamic ports. Stop it when the test ends. */
    public static WireMockServer httpsServer() {
        try {
            Path keystore = copyResource("/test-keystore.p12", ".p12");
            WireMockServer server = new WireMockServer(options()
                    .dynamicPort()
                    .dynamicHttpsPort()
                    .keystorePath(keystore.toString())
                    .keystoreType("PKCS12")
                    .keystorePassword(KEYSTORE_PASSWORD)
                    .keyManagerPassword(KEYSTORE_PASSWORD));
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException("could not start the TLS test server: " + e, e);
        }
    }

    /** A TLS context that trusts only the fixture certificate. */
    public static SSLContext trustingFixtureOnly() {
        try (InputStream pem = TlsFixture.class.getResourceAsStream("/test-ca.pem")) {
            X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pem);
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("fixture", cert);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("could not build the fixture trust context", e);
        }
    }

    /** Copies a classpath resource to a temp file, so it works when the fixture comes from a jar. */
    private static Path copyResource(String name, String suffix) throws Exception {
        Path target = Files.createTempFile("tls-fixture", suffix);
        target.toFile().deleteOnExit();
        try (InputStream in = TlsFixture.class.getResourceAsStream(name)) {
            Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
