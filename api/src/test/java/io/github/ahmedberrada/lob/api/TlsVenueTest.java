package io.github.ahmedberrada.lob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.fix.FixTestClient;
import io.github.ahmedberrada.lob.fix.TestCertificates;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
/** A secured venue open beyond loopback: REST over HTTPS and FIX over TLS, from configuration (AC-010, AC-011). */
class TlsVenueTest {

    @TempDir
    Path directory;

    @Test
    @Rulebook({"AC-010", "AC-011"})
    void restAndFixSpeakTlsAndNothingElse() throws Exception {
        Path venueKey = TestCertificates.keyStore(directory, "venue");
        Path trustVenue = TestCertificates.trustStore(directory, "trust-venue", venueKey);
        int fixPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            fixPort = socket.getLocalPort();
        }
        String hash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("admin-pass");
        String fixHash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("member-pass");
        try (ConfigurableApplicationContext venue = new SpringApplicationBuilder(LobEngineApplication.class).run(
                "--server.port=0", "--server.address=0.0.0.0",
                "--server.ssl.key-store=" + venueKey, "--server.ssl.key-store-password=" + TestCertificates.PASSWORD,
                "--server.ssl.key-store-type=PKCS12", "--server.ssl.enabled-protocols=TLSv1.3,TLSv1.2",
                "--lob.data-directory=" + directory.resolve("venue"), "--lob.fsync=OS",
                "--lob.security.users[0].name=admin", "--lob.security.users[0].password=" + hash,
                "--lob.security.users[0].roles[0]=ADMIN",
                "--lob.fix.enabled=true", "--lob.fix.bind-address=0.0.0.0", "--lob.fix.port=" + fixPort,
                "--lob.fix.passwords.MEMBER1=" + fixHash, "--lob.fix.passwords.MEMBER2=" + fixHash,
                "--lob.fix.passwords.RISK=" + fixHash,
                "--lob.fix.tls.enabled=true", "--lob.fix.tls.key-store=" + venueKey,
                "--lob.fix.tls.key-store-password=" + TestCertificates.PASSWORD,
                "--lob.binary-feed.enabled=false")) {
            int port = ((WebServerApplicationContext) venue).getWebServer().getPort();
            HttpRequest instruments = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/v1/instruments"))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:admin-pass".getBytes()))
                    .timeout(Duration.ofSeconds(10)).build();
            HttpClient https = HttpClient.newBuilder().sslContext(trusting(trustVenue)).build();
            assertThat(https.send(instruments, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

            HttpRequest clear = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/instruments"))
                    .timeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> plain = null;
            try {
                plain = HttpClient.newHttpClient().send(clear, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                // the TLS connector may close a clear connection outright
            }
            if (plain != null) {
                assertThat(plain.statusCode()).as("a clear request is not served").isEqualTo(400);
            }

            FixTestClient member = new FixTestClient("MEMBER1", fixPort, directory.resolve("client"))
                    .withTls(trustVenue, null).withPassword("member-pass");
            try {
                assertThat(member.tryLogon(10_000)).isTrue();
            } finally {
                member.close();
            }
        }
    }

    private static SSLContext trusting(Path trustStore) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(trustStore)) {
            store.load(in, TestCertificates.PASSWORD.toCharArray());
        }
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    @Test
    @Rulebook("AC-010")
    void noFixPortOpensBeforeTheCheckRefuses(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        int fixPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            fixPort = socket.getLocalPort();
        }
        String hash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("admin-pass");
        assertThatThrownBy(() -> new SpringApplicationBuilder(LobEngineApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--lob.data-directory=" + directory.resolve("fix-clear"),
                "--lob.security.users[0].name=admin", "--lob.security.users[0].password=" + hash,
                "--lob.security.users[0].roles[0]=ADMIN", "--lob.binary-feed.enabled=false",
                "--lob.fix.bind-address=0.0.0.0", "--lob.fix.port=" + fixPort,
                "--lob.fix.passwords.MEMBER1=" + hash, "--lob.fix.passwords.MEMBER2=" + hash,
                "--lob.fix.passwords.RISK=" + hash))
                .rootCause().hasMessageContaining("FIX on 0.0.0.0 (lob.fix.tls)");
        assertThat(output).doesNotContain("Listening for connections");
    }

    @Test
    @Rulebook("AC-010")
    void theSameVenueWithoutTlsDoesNotStart() {
        String hash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode("admin-pass");
        assertThatThrownBy(() -> new SpringApplicationBuilder(LobEngineApplication.class).run(
                "--server.port=0", "--server.address=0.0.0.0", "--lob.data-directory=" + directory.resolve("clear"),
                "--lob.security.users[0].name=admin", "--lob.security.users[0].password=" + hash,
                "--lob.security.users[0].roles[0]=ADMIN", "--lob.binary-feed.enabled=false", "--lob.fix.enabled=false"))
                .hasRootCauseMessage("with security enabled, these ports would listen beyond loopback without TLS: "
                        + "[REST on 0.0.0.0 (server.ssl)]; configure TLS, or declare "
                        + "lob.security.tls-terminated-in-front=true if it ends in front");
    }
}
