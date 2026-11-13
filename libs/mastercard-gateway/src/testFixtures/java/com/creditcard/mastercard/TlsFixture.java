package com.creditcard.mastercard;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Test-only TLS setup, shared through the java-test-fixtures plugin. A self-signed certificate with SAN
 * localhost and 127.0.0.1 is generated when the tests start (BouncyCastle, test scope only). The client trusts
 * only that certificate. Hostname verification stays on. Nothing here can disable TLS verification.
 */
public final class TlsFixture {

    private static final String PASSWORD = "fixture-only";
    private static final Object LOCK = new Object();
    private static Material material;

    private TlsFixture() {
    }

    /** An HTTPS WireMock server on dynamic ports, presenting the generated certificate. Stop it when the test ends. */
    public static WireMockServer httpsServer() {
        try {
            Material m = material();
            WireMockServer server = new WireMockServer(options()
                    .dynamicPort()
                    .dynamicHttpsPort()
                    .keystorePath(m.keystore().toString())
                    .keystoreType("PKCS12")
                    .keystorePassword(PASSWORD)
                    .keyManagerPassword(PASSWORD));
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException("could not start the TLS test server: " + e, e);
        }
    }

    /** A TLS context that trusts only the generated certificate. */
    public static SSLContext trustingFixtureOnly() {
        try {
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            trust.setCertificateEntry("fixture", material().certificate());
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("could not build the fixture trust context", e);
        }
    }

    private static Material material() {
        synchronized (LOCK) {
            if (material == null) {
                try {
                    material = generate();
                } catch (Exception e) {
                    throw new IllegalStateException("could not generate the fixture certificate", e);
                }
            }
            return material;
        }
    }

    private static Material generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        X500Name name = new X500Name("CN=localhost");
        long now = System.currentTimeMillis();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                BigInteger.valueOf(now), new Date(now - 60_000L), new Date(now + 365L * 24 * 3600 * 1000L),
                name, pair.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[]{
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
        builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("fixture", pair.getPrivate(), PASSWORD.toCharArray(), new Certificate[]{certificate});
        Path file = Files.createTempFile("tls-fixture", ".p12");
        file.toFile().deleteOnExit();
        try (OutputStream out = Files.newOutputStream(file)) {
            keyStore.store(out, PASSWORD.toCharArray());
        }
        return new Material(certificate, file);
    }

    private record Material(X509Certificate certificate, Path keystore) {
    }
}
