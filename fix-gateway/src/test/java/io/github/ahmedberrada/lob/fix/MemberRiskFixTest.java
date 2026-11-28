package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.MemberLimits;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SetMemberLimits;
import io.github.ahmedberrada.lob.core.SuspendMember;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
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
import quickfix.fix44.OrderMassCancelRequest;
import quickfix.field.TransactTime;
import quickfix.field.TotalAffectedOrders;
import quickfix.field.Symbol;
import quickfix.field.MassCancelResponse;
import quickfix.field.MassCancelRequestType;
import quickfix.field.MassCancelRejectReason;

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

    private static Message massCancel(String clOrdId, char type, String symbol, Character side) {
        OrderMassCancelRequest request = new OrderMassCancelRequest();
        request.setString(ClOrdID.FIELD, clOrdId);
        request.setChar(MassCancelRequestType.FIELD, type);
        if (symbol != null) {
            request.setString(Symbol.FIELD, symbol);
        }
        if (side != null) {
            request.setChar(Side.FIELD, side);
        }
        request.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        return request;
    }

    @Test
    @Rulebook("RK-006")
    void aMassCancelCancelsTheMembersOrdersInOneInstrumentOrAllAndReportsHowMany() throws Exception {
        member1.send(Orders.limit("B-1", Side.BUY, "185.00", "5"));
        member1.next();
        member1.send(Orders.limit("B-2", Side.BUY, "184.00", "5"));
        member1.next();
        member1.send(Orders.limit("S-1", Side.SELL, "190.00", "5"));
        member1.next();
        long member2 = FixGatewayConfig.selfTradeGroup("MEMBER2");
        venue.service.submit("AAPL", new io.github.ahmedberrada.lob.core.LimitOrder(
                io.github.ahmedberrada.lob.core.Side.BUY, 18_300, 5), member2, null).join();

        member1.send(massCancel("M-1", MassCancelRequestType.CANCEL_ORDERS_FOR_A_SECURITY, "AAPL", Side.BUY));
        for (String clOrdId : new String[] {"B-1", "B-2"}) {
            Message cancelled = member1.next();
            assertThat(field(cancelled, ClOrdID.FIELD)).isEqualTo(clOrdId);
            assertThat(field(cancelled, ExecType.FIELD)).isEqualTo("4");
            assertThat(field(cancelled, Text.FIELD)).isEqualTo("MASS_CANCEL");
        }
        Message report = member1.next();
        assertThat(FixTestClient.type(report)).isEqualTo("r");
        assertThat(field(report, ClOrdID.FIELD)).isEqualTo("M-1");
        assertThat(field(report, MassCancelResponse.FIELD)).isEqualTo("1");
        assertThat(field(report, TotalAffectedOrders.FIELD)).isEqualTo("2");
        assertThat(field(report, Side.FIELD)).isEqualTo("1");

        member1.send(massCancel("M-2", MassCancelRequestType.CANCEL_ALL_ORDERS, null, null));
        assertThat(field(member1.next(), ClOrdID.FIELD)).isEqualTo("S-1");
        Message all = member1.next();
        assertThat(field(all, MassCancelResponse.FIELD)).isEqualTo("7");
        assertThat(field(all, TotalAffectedOrders.FIELD)).isEqualTo("1");
        assertThat(venue.service.book("AAPL", 5).join().bids()).as("another member's order stays").hasSize(1);

        member1.send(massCancel("M-3", MassCancelRequestType.CANCEL_ALL_ORDERS, null, null));
        assertThat(field(member1.next(), TotalAffectedOrders.FIELD)).as("nothing left").isEqualTo("0");
    }

    @Test
    @Rulebook("RK-006")
    void anInvalidMassCancelIsRejectedWithTheReason() throws Exception {
        member1.send(massCancel("M-1", MassCancelRequestType.CANCEL_ORDERS_FOR_A_SECURITY, "IBM", null));
        Message unknown = member1.next();
        assertThat(field(unknown, MassCancelResponse.FIELD)).isEqualTo("0");
        assertThat(field(unknown, MassCancelRejectReason.FIELD)).isEqualTo("1");
        assertThat(field(unknown, Text.FIELD)).isEqualTo("UNKNOWN_INSTRUMENT");

        member1.send(massCancel("M-2", MassCancelRequestType.CANCEL_ORDERS_FOR_A_PRODUCT, null, null));
        assertThat(field(member1.next(), MassCancelRejectReason.FIELD)).isEqualTo("0");

        member1.send(massCancel("M-3", MassCancelRequestType.CANCEL_ALL_ORDERS, null, '7'));
        assertThat(field(member1.next(), Text.FIELD)).isEqualTo("INVALID_SIDE");

        member1.send(massCancel("M-3", MassCancelRequestType.CANCEL_ALL_ORDERS, null, null));
        Message duplicate = member1.next();
        assertThat(duplicate.isSetField(MassCancelRejectReason.FIELD)).as("FIX 4.4 has no 'other'").isFalse();
        assertThat(field(duplicate, MassCancelResponse.FIELD)).isEqualTo("0");
        assertThat(field(duplicate, Text.FIELD)).isEqualTo("DUPLICATE_CLORDID");
    }
}
