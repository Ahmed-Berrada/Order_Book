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
        Path memberKey = TestCertificates.keyStore(directory, "member1");
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
            FixTestClient anonymous = new FixTestClient("MEMBER2", venue.port, directory.resolve("anonymous"))
                    .withTls(trustVenue, null);
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
    @Rulebook("AC-011")
    void tlsNeedsTheVenuesOwnKeyStoreAndMutualTlsATrustStore() throws Exception {
        Path venueKey = TestCertificates.keyStore(directory, "venue");
        assertThatIllegalArgumentException().isThrownBy(() -> tls(directory.resolve("missing.p12"), null, false));
        assertThatIllegalArgumentException().isThrownBy(() -> tls(venueKey, null, true));
        assertThat(tls(venueKey, null, false).toString()).doesNotContain(TestCertificates.PASSWORD);
    }
}
