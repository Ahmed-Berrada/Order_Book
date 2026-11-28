package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SelfTradeMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.field.ExecType;
import quickfix.field.Side;

/** The FIX acceptor over TLS, and mutual TLS (AC-010, AC-011). */
class FixTlsTest {

    @TempDir
    Path directory;

    private FixTls tls(Path venueKey, Path trust, boolean mutual) {
        return new FixTls(venueKey, TestCertificates.PASSWORD, "PKCS12", trust, trust == null ? null : TestCertificates.PASSWORD,
                trust == null ? null : "PKCS12", mutual);
    }

    @Test
    @Rulebook({"AC-010", "AC-011"})
    void aMemberTradesOverTlsAndAClearConnectionGetsNowhere() throws Exception {
        Path venueKey = TestCertificates.keyStore(directory, "venue");
        Path trustVenue = TestCertificates.trustStore(directory, "trust-venue", venueKey);
        try (FixVenue venue = new FixVenue(directory, FixVenue.freePort(), SelfTradeMode.CANCEL_NEWEST,
                LogonAuthenticator.ALLOW_ALL, tls(venueKey, null, false))) {
            FixTestClient member = new FixTestClient("MEMBER1", venue.port, directory.resolve("tls"))
                    .withTls(trustVenue, null);
            try {
                assertThat(member.tryLogon(10_000)).isTrue();
                member.send(Orders.limit("B-1", Side.BUY, "185.00", "5"));
                assertThat(field(member.next(), ExecType.FIELD)).isEqualTo("0");
            } finally {
                member.close();
            }
            FixTestClient clear = new FixTestClient("MEMBER2", venue.port, directory.resolve("clear"));
            try {
                assertThat(clear.tryLogon(2_000)).as("no TLS: no logon").isFalse();
            } finally {
                clear.close();
            }
        }
    }

    @Test
    @Rulebook("AC-011")
    void withMutualTlsOnlyAMemberWithATrustedCertificateConnects() throws Exception {
        Path venueKey = TestCertificates.keyStore(directory, "venue");
        Path memberKey = TestCertificates.keyStore(directory, "MEMBER1");      // its common name: its CompID (AC-013)
        Path strangerKey = TestCertificates.keyStore(directory, "stranger");
        Path trustMembers = TestCertificates.trustStore(directory, "trust-members", memberKey);
        Path trustVenue = TestCertificates.trustStore(directory, "trust-venue", venueKey);
        try (FixVenue venue = new FixVenue(directory, FixVenue.freePort(), SelfTradeMode.CANCEL_NEWEST,
                LogonAuthenticator.ALLOW_ALL, tls(venueKey, trustMembers, true))) {
            FixTestClient member = new FixTestClient("MEMBER1", venue.port, directory.resolve("member"))
                    .withTls(trustVenue, memberKey);
            try {
                assertThat(member.tryLogon(10_000)).isTrue();
            } finally {
                member.close();
            }
            FixTestClient stranger = new FixTestClient("MEMBER2", venue.port, directory.resolve("stranger"))
                    .withTls(trustVenue, strangerKey);
            // an empty key store: without one, QuickFIX/J would present the demonstration certificate in its jar
            FixTestClient anonymous = new FixTestClient("MEMBER2", venue.port, directory.resolve("anonymous"))
                    .withTls(trustVenue, TestCertificates.emptyKeyStore(directory));
            try {
                assertThat(stranger.tryLogon(3_000)).as("a certificate the venue does not trust").isFalse();
                assertThat(anonymous.tryLogon(3_000)).as("no certificate").isFalse();
            } finally {
                stranger.close();
                anonymous.close();
            }
        }
    }

    @Test
    @Rulebook("AC-013")
    void aMembersCertificateLogsOnOnlyAsTheCompIdItNames() throws Exception {
        Path venueKey = TestCertificates.keyStore(directory, "venue");
        Path member1 = TestCertificates.keyStore(directory, "MEMBER1");
        Path member2 = TestCertificates.keyStore(directory, "MEMBER2");
        Path trustMembers = TestCertificates.trustStore(directory, "trust-members", member1, member2);
        Path trustVenue = TestCertificates.trustStore(directory, "trust-venue", venueKey);
        try (FixVenue venue = new FixVenue(directory, FixVenue.freePort(), SelfTradeMode.CANCEL_NEWEST,
                LogonAuthenticator.ALLOW_ALL, tls(venueKey, trustMembers, true))) {
            FixTestClient impostor = new FixTestClient("MEMBER1", venue.port, directory.resolve("impostor"))
                    .withTls(trustVenue, member2);                       // a trusted member, logging on as another
            try {
                assertThat(impostor.tryLogon(3_000)).as("MEMBER2's certificate, as MEMBER1").isFalse();
            } finally {
                impostor.close();
            }
            FixTestClient member = new FixTestClient("MEMBER2", venue.port, directory.resolve("member2"))
                    .withTls(trustVenue, member2);
            try {
                assertThat(member.tryLogon(10_000)).as("MEMBER2's certificate, as MEMBER2").isTrue();
            } finally {
                member.close();
            }
        }
        try (FixVenue unbound = new FixVenue(directory, FixVenue.freePort(), SelfTradeMode.CANCEL_NEWEST,
                LogonAuthenticator.ALLOW_ALL, new FixTls(venueKey, TestCertificates.PASSWORD, "PKCS12", trustMembers,
                        TestCertificates.PASSWORD, "PKCS12", true, false))) {
            FixTestClient other = new FixTestClient("MEMBER1", unbound.port, directory.resolve("unbound"))
                    .withTls(trustVenue, member2);
            try {
                assertThat(other.tryLogon(10_000)).as("binding switched off: any trusted member").isTrue();
            } finally {
                other.close();
            }
        }
    }

    @Test
    void aCertificateNamesItsMemberByItsOneCommonName() throws Exception {
        java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(TestCertificates.keyStore(directory, "MEMBER9"))) {
            store.load(in, TestCertificates.PASSWORD.toCharArray());
        }
        var certificate = (java.security.cert.X509Certificate) store.getCertificate(store.aliases().nextElement());
        assertThat(PeerCertificates.commonNames(certificate)).containsExactly("MEMBER9");
        assertThat(new PeerCertificates().names("127.0.0.1:1", "MEMBER9")).as("no such connection").isFalse();
        assertThat(new PeerCertificates().names(null, "MEMBER9")).isFalse();
    }

    @Test
    @Rulebook("AC-011")
    void tlsNeedsTheVenuesOwnKeyStoreAndMutualTlsATrustStore() throws Exception {
        Path venueKey = TestCertificates.keyStore(directory, "venue");
        assertThatIllegalArgumentException().isThrownBy(() -> tls(directory.resolve("missing.p12"), null, false));
        assertThatIllegalArgumentException().isThrownBy(() -> tls(venueKey, null, true));
        assertThatIllegalArgumentException().as("binding needs mutual TLS").isThrownBy(() -> new FixTls(venueKey,
                TestCertificates.PASSWORD, "PKCS12", null, null, null, false, true));
        assertThat(tls(venueKey, null, false).toString()).doesNotContain(TestCertificates.PASSWORD);
    }
}
