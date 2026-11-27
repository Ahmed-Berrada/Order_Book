package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.MemberLimits;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SetMemberLimits;
import io.github.ahmedberrada.lob.core.SuspendMember;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Message;
import quickfix.field.ClOrdID;
import quickfix.field.ExecType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.Side;
import quickfix.field.Text;

/** Pre-trade limits and the kill switch for FIX members: a session acts for the member derived from its CompID. */
class MemberRiskFixTest {

    private static final long MEMBER1 = FixGatewayConfig.selfTradeGroup("MEMBER1");

    @TempDir
    Path directory;

    private FixVenue venue;
    private FixTestClient member1;

    @BeforeEach
    void connect() throws Exception {
        venue = new FixVenue(directory, FixVenue.freePort());
        member1 = new FixTestClient("MEMBER1", venue.port, directory).logon();
    }

    @AfterEach
    void disconnect() throws Exception {
        member1.close();
        venue.close();
    }

    @Test
    @Rulebook("RK-002")
    void anOrderBeyondTheMembersLimitIsRejectedAsExceedingALimit() throws Exception {
        venue.service.submit("AAPL", new SetMemberLimits(MEMBER1, new MemberLimits(10, 0, 0, 0, 0))).join();

        member1.send(Orders.limit("B-1", Side.BUY, "185.00", "11"));

        Message reject = member1.next();
        assertThat(field(reject, ClOrdID.FIELD)).isEqualTo("B-1");
        assertThat(field(reject, ExecType.FIELD)).isEqualTo("8");
        assertThat(field(reject, OrdRejReason.FIELD)).isEqualTo("3");
        assertThat(field(reject, Text.FIELD)).isEqualTo("RISK_ORDER_QUANTITY");

        member1.send(Orders.limit("B-2", Side.BUY, "185.00", "10"));
        assertThat(field(member1.next(), ExecType.FIELD)).isEqualTo("0");
    }

    @Test
    @Rulebook({"RK-004", "MB-002"})
    void theKillSwitchCancelsTheMembersOrdersAndBlocksItsSession() throws Exception {
        member1.send(Orders.limit("B-1", Side.BUY, "185.00", "5"));
        member1.next();

        venue.service.submit("AAPL", new SuspendMember(MEMBER1)).join();

        Message cancelled = member1.next();
        assertThat(field(cancelled, ClOrdID.FIELD)).isEqualTo("B-1");
        assertThat(field(cancelled, ExecType.FIELD)).isEqualTo("4");
        assertThat(field(cancelled, OrdStatus.FIELD)).isEqualTo("4");
        assertThat(field(cancelled, Text.FIELD)).isEqualTo("KILL_SWITCH");

        member1.send(Orders.limit("B-2", Side.BUY, "185.00", "5"));
        Message reject = member1.next();
        assertThat(field(reject, ExecType.FIELD)).isEqualTo("8");
        assertThat(field(reject, Text.FIELD)).isEqualTo("MEMBER_SUSPENDED");
        assertThat(venue.service.restingQuantity("AAPL", 1).join()).isZero();
    }
}
