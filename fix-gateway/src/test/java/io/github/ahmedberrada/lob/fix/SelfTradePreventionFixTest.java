package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SelfTradeMode;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecType;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.Side;
import quickfix.field.Text;

/** Self-trade prevention over FIX: every member session is its own group (SP-001 to SP-004). */
class SelfTradePreventionFixTest {

    @TempDir
    Path directory;

    private FixVenue venue;
    private FixTestClient member1;
    private FixTestClient member2;

    private void connect(SelfTradeMode mode) throws Exception {
        venue = new FixVenue(directory, FixVenue.freePort(), mode);
        member1 = new FixTestClient("MEMBER1", venue.port, directory).logon();
        member2 = new FixTestClient("MEMBER2", venue.port, directory).logon();
    }

    @AfterEach
    void disconnect() throws Exception {
        if (venue != null) {
            member1.close();
            member2.close();
            venue.close();
        }
    }

    private static void assertReport(Message report, Map<Integer, String> expected) throws FieldNotFound {
        assertThat(FixTestClient.type(report)).isEqualTo(MsgType.EXECUTION_REPORT);
        for (var field : expected.entrySet()) {
            assertThat(field(report, field.getKey())).as("tag %d", field.getKey()).isEqualTo(field.getValue());
        }
    }

    @Test
    @Rulebook({"SP-001", "SP-002"})
    void aMembersOrderThatMeetsItsOwnIsCancelledAndAnotherMemberStillTrades() throws Exception {
        connect(SelfTradeMode.CANCEL_NEWEST);
        member1.send(Orders.limit("S-1", Side.SELL, "185.25", "5"));
        member1.next();

        member1.send(Orders.limit("B-1", Side.BUY, "185.25", "3"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "B-1", OrderID.FIELD, "AAPL-2", ExecType.FIELD, "0"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "B-1", ExecType.FIELD, "4", OrdStatus.FIELD, "4",
                CumQty.FIELD, "0", LeavesQty.FIELD, "0", Text.FIELD, "SELF_TRADE_PREVENTION"));
        assertThat(venue.service.restingQuantity("AAPL", 1).join()).isEqualTo(5);
        assertThat(venue.service.restingQuantity("AAPL", 2).join()).isZero();

        // the other member is a different group, and trades with the order
        member2.send(Orders.limit("B-2", Side.BUY, "185.25", "2"));
        assertReport(member2.next(), Map.of(ClOrdID.FIELD, "B-2", ExecType.FIELD, "0"));
        assertReport(member2.next(), Map.of(ClOrdID.FIELD, "B-2", ExecType.FIELD, "F", OrdStatus.FIELD, "2"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "S-1", ExecType.FIELD, "F", LeavesQty.FIELD, "3"));
    }

    @Test
    @Rulebook({"SP-003", "SP-005"})
    void cancelOldestReportsTheCancelledRestingOrderToItsOwner() throws Exception {
        connect(SelfTradeMode.CANCEL_OLDEST);
        member1.send(Orders.limit("S-1", Side.SELL, "185.25", "5"));
        member1.next();

        member1.send(Orders.limit("B-1", Side.BUY, "185.25", "3"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "B-1", ExecType.FIELD, "0"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "S-1", OrderID.FIELD, "AAPL-1", ExecType.FIELD, "4",
                OrdStatus.FIELD, "4", LeavesQty.FIELD, "0", Text.FIELD, "SELF_TRADE_PREVENTION"));
        // the new order rests: no further report
        member1.expectNothing(java.time.Duration.ofMillis(200));
        assertThat(venue.service.restingQuantity("AAPL", 1).join()).isZero();
        assertThat(venue.service.restingQuantity("AAPL", 2).join()).isEqualTo(3);
    }

    @Test
    @Rulebook({"SP-004", "SP-005"})
    void cancelBothReportsBothOrders() throws Exception {
        connect(SelfTradeMode.CANCEL_BOTH);
        member1.send(Orders.limit("S-1", Side.SELL, "185.25", "5"));
        member1.next();

        member1.send(Orders.market("B-1", Side.BUY, "3"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "B-1", ExecType.FIELD, "0"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "S-1", ExecType.FIELD, "4", Text.FIELD, "SELF_TRADE_PREVENTION"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "B-1", ExecType.FIELD, "4", OrdStatus.FIELD, "4",
                Text.FIELD, "SELF_TRADE_PREVENTION"));
        assertThat(venue.service.restingQuantity("AAPL", 1).join()).isZero();
    }

    @Test
    @Rulebook("SP-001")
    void ordersFromOtherChannelsHaveNoGroup() throws Exception {
        connect(SelfTradeMode.CANCEL_BOTH);
        // an order entered through the service, as the REST API does, has no group: it trades with anyone
        venue.service.submit("AAPL", new io.github.ahmedberrada.lob.core.LimitOrder(
                io.github.ahmedberrada.lob.core.Side.SELL, 18_525, 5)).join();
        member1.send(Orders.limit("B-1", Side.BUY, "185.25", "3"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "B-1", ExecType.FIELD, "0"));
        assertReport(member1.next(), Map.of(ClOrdID.FIELD, "B-1", ExecType.FIELD, "F", OrdStatus.FIELD, "2"));
    }
}
