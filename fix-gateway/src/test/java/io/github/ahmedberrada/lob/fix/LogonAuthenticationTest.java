package io.github.ahmedberrada.lob.fix;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SelfTradeMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A session logs on only with a password the application accepts. */
class LogonAuthenticationTest {

    @TempDir
    Path directory;

    @Test
    @Rulebook({"AC-001", "AC-007"})
    void aSessionWithTheRightPasswordLogsOnAndOthersAreRefused() throws Exception {
        List<String> attempts = new ArrayList<>();
        LogonAuthenticator authenticator = (compId, password) -> {
            attempts.add(compId + ":" + password);
            return "MEMBER1".equals(compId) && "s3cret".equals(password);
        };
        try (FixVenue venue = new FixVenue(directory, FixVenue.freePort(), SelfTradeMode.CANCEL_NEWEST, authenticator)) {
            FixTestClient wrong = new FixTestClient("MEMBER1", venue.port, directory.resolve("wrong"))
                    .withPassword("guess");
            FixTestClient missing = new FixTestClient("MEMBER2", venue.port, directory.resolve("missing"));
            FixTestClient right = new FixTestClient("MEMBER1", venue.port, directory.resolve("right"))
                    .withPassword("s3cret");
            try {
                assertThat(wrong.tryLogon(2_000)).isFalse();
                assertThat(missing.tryLogon(2_000)).isFalse();
            } finally {
                wrong.close();
                missing.close();
            }
            try {
                assertThat(right.tryLogon(10_000)).isTrue();
            } finally {
                right.close();
            }
        }

        assertThat(attempts).contains("MEMBER1:guess", "MEMBER2:null", "MEMBER1:s3cret");
    }

    @Test
    @Rulebook("AC-009")
    void theAuthenticatorIsToldWhereTheLogonCameFrom() throws Exception {
        List<String> addresses = new java.util.concurrent.CopyOnWriteArrayList<>();
        LogonAuthenticator authenticator = new LogonAuthenticator() {
            @Override
            public boolean authenticate(String compId, String password) {
                throw new AssertionError("the gateway passes the address");
            }

            @Override
            public boolean authenticate(String compId, String password, String remoteAddress) {
                addresses.add(remoteAddress);
                return "s3cret".equals(password);
            }
        };
        try (FixVenue venue = new FixVenue(directory, FixVenue.freePort(), SelfTradeMode.CANCEL_NEWEST, authenticator)) {
            FixTestClient client = new FixTestClient("MEMBER1", venue.port, directory.resolve("client"))
                    .withPassword("s3cret");
            try {
                assertThat(client.tryLogon(10_000)).isTrue();
            } finally {
                client.close();
            }
        }
        assertThat(addresses).isNotEmpty().allSatisfy(a -> assertThat(LoginThrottleHost.of(a)).isEqualTo("127.0.0.1"));
    }

    /** The host part of a socket address as text, as the application keys lockouts on it. */
    private static final class LoginThrottleHost {
        static String of(String address) {
            String a = address.substring(address.lastIndexOf('/') + 1);
            return a.substring(0, a.lastIndexOf(':'));
        }
    }
}
