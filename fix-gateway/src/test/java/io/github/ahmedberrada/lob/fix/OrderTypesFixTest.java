package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecInst;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.OrderStatusRequest;

/** Time in force, post-only and OrderCancelReplaceRequest over FIX (Rules of Engagement). */
class OrderTypesFixTest {

    @TempDir
    Path directory;

    private FixVenue venue;
    private FixTestClient member1;
    private FixTestClient member2;

    @BeforeEach
    void connect() throws Exception {
        venue = new FixVenue(directory, FixVenue.freePort());
        member1 = new FixTestClient("MEMBER1", venue.port, directory).logon();
        member2 = new FixTestClient("MEMBER2", venue.port, directory).logon();
    }

    @AfterEach
    void disconnect() throws Exception {
        member1.close();
        member2.close();
        venue.close();
    }

    private static void assertReport(Message report, Map<Integer, String> expected) throws FieldNotFound {
        assertThat(FixTestClient.type(report)).isEqualTo(MsgType.EXECUTION_REPORT);
        for (var field : expected.entrySet()) {
            assertThat(field(report, field.getKey())).as("tag %d", field.getKey()).isEqualTo(field.getValue());
        }
    }

    private static Message withTif(Message order, char tif) {
        order.setChar(TimeInForce.FIELD, tif);
        return order;
    }

    private static Message postOnly(Message order) {
        order.setString(ExecInst.FIELD, String.valueOf(ExecInst.PARTICIPATE_DONT_INITIATE));
        return order;
    }

    private static Message replace(String clOrdId, String origClOrdId, char side, String price, String quantity) {
        OrderCancelReplaceRequest request = new OrderCancelReplaceRequest();
        request.setString(ClOrdID.FIELD, clOrdId);
        request.setString(OrigClOrdID.FIELD, origClOrdId);
        request.setString(Symbol.FIELD, "AAPL");
        request.setChar(Side.FIELD, side);
        request.setChar(OrdType.FIELD, OrdType.LIMIT);
        request.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        if (price != null) {
            request.setDecimal(Price.FIELD, new BigDecimal(price));
        }
        request.setDecimal(OrderQty.FIELD, new BigDecimal(quantity));
        return request;
    }

    private static void assertReplaceReject(Message reject, int reason, String text) throws Exception {
        assertThat(FixTestClient.type(reject)).isEqualTo(MsgType.ORDER_CANCEL_REJECT);
        assertThat(reject.getChar(CxlRejResponseTo.FIELD)).isEqualTo(CxlRejResponseTo.ORDER_CANCEL_REPLACE_REQUEST);
        assertThat(reject.getInt(CxlRejReason.FIELD)).isEqualTo(reason);
        assertThat(field(reject, Text.FIELD)).isEqualTo(text);
    }

    // ---- Time in force and post-only --------------------------------------------------------------

    @Test
    @Rulebook({"OT-002", "OT-003"})
    void iocAndFokOrders() throws Exception {
        member2.send(Orders.limit("S-1", Side.SELL, "185.25", "3"));
        member2.next();

        member1.send(withTif(Orders.limit("B-1", Side.BUY, "185.25", "5"), TimeInForce.FILL_OR_KILL));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-2", ExecType.FIELD, "0"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "4", OrdStatus.FIELD, "4", CumQty.FIELD, "0",
                LeavesQty.FIELD, "0", Text.FIELD, "FILL_OR_KILL"));

        member1.send(withTif(Orders.limit("B-2", Side.BUY, "185.25", "5"), TimeInForce.IMMEDIATE_OR_CANCEL));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-3", ExecType.FIELD, "0"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "F", LastQty.FIELD, "3", LeavesQty.FIELD, "2"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "4", OrdStatus.FIELD, "4", CumQty.FIELD, "3",
                LeavesQty.FIELD, "0", Text.FIELD, "IMMEDIATE_OR_CANCEL"));
        assertReport(member2.next(), Map.of(ExecType.FIELD, "F", OrdStatus.FIELD, "2"));
    }

    @Test
    @Rulebook("OT-001")
    void marketOrdersAreImmediateOrCancelAndRefuseGoodTillCancel() throws Exception {
        member2.send(Orders.limit("S-1", Side.SELL, "185.25", "3"));
        member2.next();

        member1.send(withTif(Orders.market("B-1", Side.BUY, "1"), TimeInForce.GOOD_TILL_CANCEL));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-2", ExecType.FIELD, "8",
                OrdRejReason.FIELD, "11", Text.FIELD, "INVALID_TIME_IN_FORCE"));
        member1.send(withTif(Orders.market("B-2", Side.BUY, "4"), TimeInForce.FILL_OR_KILL));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-3", ExecType.FIELD, "0"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "4", Text.FIELD, "FILL_OR_KILL"));
        member1.send(withTif(Orders.market("B-3", Side.BUY, "1"), TimeInForce.DAY));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-4", ExecType.FIELD, "0"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "F", OrdStatus.FIELD, "2"));
    }

    @Test
    @Rulebook("OT-004")
    void postOnlyOrders() throws Exception {
        member2.send(Orders.limit("S-1", Side.SELL, "185.25", "3"));
        member2.next();

        member1.send(postOnly(Orders.limit("B-1", Side.BUY, "185.25", "1")));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-2", ExecType.FIELD, "8",
                OrdRejReason.FIELD, "99", Text.FIELD, "POST_ONLY_WOULD_TRADE"));
        member1.send(postOnly(Orders.limit("B-2", Side.BUY, "185.20", "1")));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-3", ExecType.FIELD, "0", OrdStatus.FIELD, "0"));
        member1.send(withTif(postOnly(Orders.limit("B-3", Side.BUY, "185.20", "1")), TimeInForce.IMMEDIATE_OR_CANCEL));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-4", ExecType.FIELD, "8",
                OrdRejReason.FIELD, "11", Text.FIELD, "INVALID_TIME_IN_FORCE"));
    }

    // ---- Cancel/replace ----------------------------------------------------------------------------

    @Test
    @Rulebook({"OT-005", "OT-006", "OT-008"})
    void replaceReportsTheNewClOrdIdAndQuantity() throws Exception {
        member1.send(Orders.limit("B-1", Side.BUY, "185.00", "10"));
        member1.next();

        Message replaced = member1.next(replace("B-2", "B-1", Side.BUY, "185.00", "6"));
        assertReport(replaced, Map.of(ExecType.FIELD, "5", OrdStatus.FIELD, "0", ClOrdID.FIELD, "B-2",
                OrigClOrdID.FIELD, "B-1", OrderID.FIELD, "AAPL-1", OrderQty.FIELD, "6", LeavesQty.FIELD, "6",
                CumQty.FIELD, "0", Price.FIELD, "185.00", ExecID.FIELD, "AAPL-3"));

        // the order is now known by its new ClOrdID, for status and cancel
        OrderStatusRequest status = new OrderStatusRequest();
        status.setString(ClOrdID.FIELD, "B-2");
        status.setString(Symbol.FIELD, "AAPL");
        status.setChar(Side.FIELD, Side.BUY);
        assertReport(member1.next(status), Map.of(ExecType.FIELD, "I", ClOrdID.FIELD, "B-2", LeavesQty.FIELD, "6"));
        OrderCancelRequest cancel = new OrderCancelRequest();
        cancel.setString(ClOrdID.FIELD, "C-1");
        cancel.setString(OrigClOrdID.FIELD, "B-2");
        cancel.setString(Symbol.FIELD, "AAPL");
        cancel.setChar(Side.FIELD, Side.BUY);
        cancel.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        assertReport(member1.next(cancel), Map.of(ExecType.FIELD, "4", OrigClOrdID.FIELD, "B-2",
                CumQty.FIELD, "0", LeavesQty.FIELD, "0"));
    }

    @Test
    @Rulebook({"OT-005", "OT-007", "OT-008"})
    void aggressiveReplaceTradesAndOrderQtyIncludesFills() throws Exception {
        member1.send(Orders.limit("B-1", Side.BUY, "185.00", "10"));
        member1.next();
        member2.send(Orders.market("S-1", Side.SELL, "4"));               // fills 4 of B-1
        member2.next();
        member2.next();
        assertReport(member1.next(), Map.of(ExecType.FIELD, "F", CumQty.FIELD, "4", LeavesQty.FIELD, "6"));
        member2.send(Orders.limit("S-2", Side.SELL, "185.10", "2"));
        member2.next();

        // OrderQty 9 with 4 filled: 5 remain, 2 of which trade at once
        member1.send(replace("B-2", "B-1", Side.BUY, "185.10", "9"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "5", OrdStatus.FIELD, "1", ClOrdID.FIELD, "B-2",
                OrigClOrdID.FIELD, "B-1", OrderQty.FIELD, "9", CumQty.FIELD, "4", LeavesQty.FIELD, "5"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "F", ClOrdID.FIELD, "B-2", LastPx.FIELD, "185.10",
                LastQty.FIELD, "2", CumQty.FIELD, "6", LeavesQty.FIELD, "3", OrderQty.FIELD, "9"));
        assertReport(member2.next(), Map.of(ExecType.FIELD, "F", ClOrdID.FIELD, "S-2", OrdStatus.FIELD, "2"));
        assertThat(venue.service.restingQuantity("AAPL", 1).join()).isEqualTo(3);
    }

    @Test
    @Rulebook("OT-005")
    void refusedReplacesLeaveTheOrderUnchanged() throws Exception {
        member1.send(Orders.limit("B-1", Side.BUY, "185.00", "10"));
        member1.next();
        member2.send(Orders.market("S-1", Side.SELL, "4"));
        member2.next();
        member2.next();
        member1.next();

        assertReplaceReject(member1.next(replace("B-2", "B-1", Side.BUY, "185.00", "4")),
                CxlRejReason.OTHER, "QUANTITY_NOT_ABOVE_FILLED");
        assertReplaceReject(member1.next(replace("B-3", "B-1", Side.BUY, "185.005", "8")),
                CxlRejReason.OTHER, "OFF_TICK_PRICE");
        assertReplaceReject(member1.next(replace("B-4", "B-1", Side.BUY, null, "8")),
                CxlRejReason.OTHER, "ONLY_LIMIT_PRICE_AND_QUANTITY");
        assertReplaceReject(member1.next(replace("B-5", "B-1", Side.BUY, "185.00", "8.5")),
                CxlRejReason.OTHER, "FRACTIONAL_QUANTITY");
        assertReplaceReject(member1.next(replace("B-6", "NOPE", Side.BUY, "185.00", "8")),
                CxlRejReason.UNKNOWN_ORDER, "UNKNOWN_ORDER");
        assertReplaceReject(member1.next(replace("B-7", "B-1", Side.SELL, "185.00", "8")),
                CxlRejReason.UNKNOWN_ORDER, "UNKNOWN_ORDER");
        assertReplaceReject(member1.next(replace("B-1", "B-1", Side.BUY, "185.00", "8")),
                CxlRejReason.DUPLICATE_CLORDID_RECEIVED, "DUPLICATE_CLORDID");
        // another member cannot see the order
        assertReplaceReject(member2.next(replace("X-1", "B-1", Side.BUY, "185.00", "8")),
                CxlRejReason.UNKNOWN_ORDER, "UNKNOWN_ORDER");
        // the engine refuses a quantity above the maximum; a refused ClOrdID cannot be reused
        assertReplaceReject(member1.next(replace("B-8", "B-1", Side.BUY, "185.00", "2000000")),
                CxlRejReason.OTHER, "QUANTITY_ABOVE_MAXIMUM");
        assertReplaceReject(member1.next(replace("B-8", "B-1", Side.BUY, "185.00", "8")),
                CxlRejReason.DUPLICATE_CLORDID_RECEIVED, "DUPLICATE_CLORDID");

        assertThat(venue.service.restingQuantity("AAPL", 1).join()).isEqualTo(6);
        Message reject = member1.next(replace("B-9", "B-1", Side.BUY, "185.00", "8.5"));
        assertThat(field(reject, OrderID.FIELD)).isEqualTo("AAPL-1");
        assertThat(field(reject, OrdStatus.FIELD)).isEqualTo("1");
    }

    @Test
    @Rulebook({"OT-004", "OT-005"})
    void postOnlyOrderCannotBeReplacedIntoATradeAndAFilledOrderIsTooLate() throws Exception {
        member2.send(Orders.limit("S-1", Side.SELL, "185.10", "2"));
        member2.next();
        member1.send(postOnly(Orders.limit("B-1", Side.BUY, "185.00", "3")));
        member1.next();

        assertReplaceReject(member1.next(replace("B-2", "B-1", Side.BUY, "185.10", "3")),
                CxlRejReason.OTHER, "POST_ONLY_WOULD_TRADE");

        member2.send(Orders.market("S-2", Side.SELL, "3"));                 // fills B-1 entirely
        member2.next();
        member2.next();
        member1.next();
        // the order was found but no longer rests: the engine answers, too late
        assertReplaceReject(member1.next(replace("B-3", "B-1", Side.BUY, "185.00", "5")),
                CxlRejReason.TOO_LATE_TO_CANCEL, "NOT_RESTING");
    }

    @Test
    @Rulebook("OT-008")
    void replaceByAnotherChannelIsReportedToTheOwner() throws Exception {
        member1.send(Orders.limit("B-1", Side.BUY, "185.00", "10"));
        member1.next();

        venue.service.submit("AAPL", new ReplaceOrder(1, 18_490, 7)).join();
        assertReport(member1.next(), Map.of(ExecType.FIELD, "5", ClOrdID.FIELD, "B-1", OrigClOrdID.FIELD, "B-1",
                OrderQty.FIELD, "7", LeavesQty.FIELD, "7", Price.FIELD, "184.90"));
        // a replace of an order no FIX session owns is not reported to anyone
        member2.send(Orders.limit("S-1", Side.SELL, "190.00", "1"));
        member2.next();
        venue.service.submit("AAPL", new ReplaceOrder(99, 18_490, 7)).join();
        venue.service.submit("AAPL", new ReplaceOrder(2, 19_010, 1)).join();
        assertReport(member2.next(), Map.of(ExecType.FIELD, "5", Price.FIELD, "190.10"));
    }
}
