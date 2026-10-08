package io.github.ahmedberrada.lob.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Message;
import quickfix.field.BusinessRejectReason;
import quickfix.field.MsgType;
import quickfix.field.SecurityReqID;
import quickfix.field.SecurityRequestType;
import quickfix.fix44.SecurityDefinitionRequest;

/** Session layer: logon, provisioned sessions only, unsupported application messages. */
class SessionTest {

    @TempDir
    Path directory;

    private FixVenue venue;

    @BeforeEach
    void startVenue() throws Exception {
        venue = new FixVenue(directory, FixVenue.freePort());
    }

    @AfterEach
    void stopVenue() throws Exception {
        venue.close();
    }

    @Test
    void provisionedMemberLogsOn() throws Exception {
        try (FixTestClient member = new FixTestClient("MEMBER1", venue.port, directory).logon()) {
            assertThat(member.isLoggedOn()).isTrue();
        }
    }

    @Test
    void unknownCompIdCannotLogOn() throws Exception {
        try (FixTestClient stranger = new FixTestClient("STRANGER", venue.port, directory)) {
            assertThatThrownBy(stranger::logon).isInstanceOf(AssertionError.class);
            assertThat(stranger.isLoggedOn()).isFalse();
        }
    }

    @Test
    void unsupportedMessagesGetABusinessReject() throws Exception {
        try (FixTestClient member = new FixTestClient("MEMBER1", venue.port, directory).logon()) {
            member.send(new SecurityDefinitionRequest(new SecurityReqID("S1"),
                    new SecurityRequestType(SecurityRequestType.REQUEST_LIST_SECURITIES)));

            Message reject = member.next();
            assertThat(FixTestClient.type(reject)).isEqualTo(MsgType.BUSINESS_MESSAGE_REJECT);
            assertThat(reject.getInt(BusinessRejectReason.FIELD)).isEqualTo(BusinessRejectReason.UNSUPPORTED_MESSAGE_TYPE);
            member.expectNothing(Duration.ofMillis(200));
        }
    }

    @Test
    void validatesItsConfiguration() {
        Path store = directory.resolve("store");
        assertThatIllegalArgumentException().isThrownBy(() -> new FixGatewayConfig(0, "LOB", List.of("M"), store));
        assertThatIllegalArgumentException().isThrownBy(() -> new FixGatewayConfig(70_000, "LOB", List.of("M"), store));
        assertThatIllegalArgumentException().isThrownBy(() -> new FixGatewayConfig(9_000, "LOB", List.of(), store));
        assertThatIllegalArgumentException().isThrownBy(() -> new FixGatewayConfig(9_000, "LOB", List.of("M", "M"), store));
        assertThatNullPointerException().isThrownBy(() -> new FixGatewayConfig(9_000, null, List.of("M"), store));
        assertThatNullPointerException().isThrownBy(() -> FixGateway.start(null,
                new FixGatewayConfig(9_000, "LOB", List.of("M"), store)));
    }
}
