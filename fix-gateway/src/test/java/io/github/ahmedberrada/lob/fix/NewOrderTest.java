package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.AvgPx;
import quickfix.field.BusinessRejectReason;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
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
import quickfix.field.Price;
import quickfix.field.RefMsgType;
import quickfix.field.Side;
import quickfix.field.Text;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;

/** Order entry as a member sees it: NewOrderSingle in, ExecutionReports out. */
class NewOrderTest {

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

    /** Asserts the report's fields, compared as FIX text. */
    private static void assertReport(Message report, Map<Integer, String> expected) throws FieldNotFound {
        assertThat(FixTestClient.type(report)).isEqualTo(MsgType.EXECUTION_REPORT);
        for (var field : expected.entrySet()) {
            assertThat(field(report, field.getKey())).as("tag %d", field.getKey()).isEqualTo(field.getValue());
        }
    }

    @Test
    @Rulebook({"OE-002", "OE-004", "RS-006"})
    void limitOrderIsAcknowledgedWithMicrosecondTimestamp() throws Exception {
        member1.send(Orders.limit("B-1", Side.BUY, "185.25", "10"));

        Message report = member1.next();
        assertReport(report, Map.of(ClOrdID.FIELD, "B-1", OrderID.FIELD, "AAPL-1", ExecID.FIELD, "AAPL-1",
                ExecType.FIELD, "0", OrdStatus.FIELD, "0", LeavesQty.FIELD, "10", CumQty.FIELD, "0", AvgPx.FIELD, "0"));
        assertThat(field(report, TransactTime.FIELD)).matches("\\d{8}-\\d\\d:\\d\\d:\\d\\d\\.\\d{6}");
    }

    @Test
    void takerGetsNewThenOneTradeReportPerFill() throws Exception {
        member2.send(Orders.limit("S-1", Side.SELL, "185.30", "3"));
        member2.send(Orders.limit("S-2", Side.SELL, "185.40", "3"));
        member2.next();
        member2.next();

        member1.send(Orders.limit("B-1", Side.BUY, "185.40", "5"));

        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-3", ExecType.FIELD, "0", LeavesQty.FIELD, "5"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "F", OrdStatus.FIELD, "1", LastPx.FIELD, "185.30",
                LastQty.FIELD, "3", CumQty.FIELD, "3", LeavesQty.FIELD, "2", AvgPx.FIELD, "185.3"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "F", OrdStatus.FIELD, "2", LastPx.FIELD, "185.40",
                LastQty.FIELD, "2", CumQty.FIELD, "5", LeavesQty.FIELD, "0", AvgPx.FIELD, "185.34"));
        member1.expectNothing(Duration.ofMillis(200));
    }

    @Test
    void marketRemainderIsReportedCanceled() throws Exception {
        member2.send(Orders.limit("S-1", Side.SELL, "100.00", "1"));
        member2.next();

        member1.send(Orders.market("B-1", Side.BUY, "5"));

        assertReport(member1.next(), Map.of(ExecType.FIELD, "0"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "F", CumQty.FIELD, "1"));
        assertReport(member1.next(), Map.of(ExecType.FIELD, "4", OrdStatus.FIELD, "4", CumQty.FIELD, "1",
                LeavesQty.FIELD, "0"));
    }

    @Test
    void engineRejectionsCarryTheConsumedOrderId() throws Exception {
        member1.send(Orders.limit("B-1", Side.BUY, "185.25", "0"));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-1", ExecType.FIELD, "8", OrdStatus.FIELD, "8",
                OrdRejReason.FIELD, "13", Text.FIELD, "INVALID_QUANTITY"));

        member1.send(Orders.limit("B-2", Side.BUY, "185.25", "1001"));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-2", OrdRejReason.FIELD, "3",
                Text.FIELD, "QUANTITY_ABOVE_MAXIMUM"));

        member1.send(Orders.market("B-3", Side.BUY, "1"));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-3", OrdRejReason.FIELD, "99", Text.FIELD, "NO_LIQUIDITY"));
    }

    @Test
    @Rulebook({"OE-001", "OE-002"})
    void refusalsBeforeTheEngineHaveNoOrderId() throws Exception {
        Message offTick = Orders.limit("B-1", Side.BUY, "185.255", "1");
        Message unknownSymbol = Orders.order("B-2", "TSLA", Side.BUY, OrdType.MARKET, "1");
        Message fractional = Orders.market("B-3", Side.BUY, "1.5");
        Message ioc = Orders.limit("B-4", Side.BUY, "185.25", "1");
        ioc.setChar(TimeInForce.FIELD, TimeInForce.IMMEDIATE_OR_CANCEL);
        Message limitWithoutPrice = Orders.order("B-5", "AAPL", Side.BUY, OrdType.LIMIT, "1");
        Message marketWithPrice = Orders.market("B-6", Side.BUY, "1");
        marketWithPrice.setString(Price.FIELD, "185.25");
        Message sellShort = Orders.market("B-7", Side.SELL_SHORT, "1");
        Message stopOrder = Orders.order("B-8", "AAPL", Side.BUY, OrdType.STOP, "1");

        Object[][] cases = {
            {offTick, "99", "OFF_TICK_PRICE"}, {unknownSymbol, "1", "UNKNOWN_INSTRUMENT"},
            {fractional, "13", "FRACTIONAL_QUANTITY"}, {ioc, "11", "UNSUPPORTED_TIME_IN_FORCE"},
            {limitWithoutPrice, "11", "PRICE_REQUIRED_FOR_LIMIT"}, {marketWithPrice, "11", "PRICE_NOT_ALLOWED_FOR_MARKET"},
            {sellShort, "11", "UNSUPPORTED_SIDE"}, {stopOrder, "11", "UNSUPPORTED_ORD_TYPE"},
        };
        for (Object[] c : cases) {
            member1.send((Message) c[0]);
            assertReport(member1.next(), Map.of(OrderID.FIELD, "NONE", ExecType.FIELD, "8", OrdStatus.FIELD, "8",
                    OrdRejReason.FIELD, (String) c[1], Text.FIELD, (String) c[2]));
        }

        // none of them reached the engine: the next order gets ID 1
        member1.send(Orders.limit("B-9", Side.BUY, "185.25", "1"));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-1"));
    }

    @Test
    void dayTimeInForceIsAccepted() throws Exception {
        Message day = Orders.limit("B-1", Side.BUY, "185.25", "1");
        day.setChar(TimeInForce.FIELD, TimeInForce.DAY);

        assertReport(member1.next(day), Map.of(OrderID.FIELD, "AAPL-1", ExecType.FIELD, "0"));
    }

    @Test
    void duplicateClOrdIdIsRejectedPerSession() throws Exception {
        member1.send(Orders.limit("SAME", Side.BUY, "185.25", "1"));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "AAPL-1", ExecType.FIELD, "0"));

        member1.send(Orders.limit("SAME", Side.BUY, "185.25", "1"));
        assertReport(member1.next(), Map.of(OrderID.FIELD, "NONE", OrdRejReason.FIELD, "6"));

        member2.send(Orders.limit("SAME", Side.BUY, "185.25", "1"));     // another session may reuse it
        assertReport(member2.next(), Map.of(OrderID.FIELD, "AAPL-2", ExecType.FIELD, "0"));
    }

    @Test
    void missingOrderQtyIsABusinessReject() throws Exception {
        // OrderQty sits in an optional component in FIX 4.4, so the dictionary accepts the message
        // and the application rejects it: "conditionally required field missing".
        member1.send(Orders.order("B-1", "AAPL", Side.BUY, OrdType.MARKET, null));

        Message reject = member1.next();
        assertThat(FixTestClient.type(reject)).isEqualTo(MsgType.BUSINESS_MESSAGE_REJECT);
        assertThat(reject.getInt(BusinessRejectReason.FIELD))
                .isEqualTo(BusinessRejectReason.CONDITIONALLY_REQUIRED_FIELD_MISSING);
        assertThat(reject.getString(RefMsgType.FIELD)).isEqualTo(MsgType.NEW_ORDER_SINGLE);
        assertThat(reject.getString(Text.FIELD)).endsWith("field=" + OrderQty.FIELD);
    }

    @Test
    void invalidFieldValueIsASessionReject() throws Exception {
        Message order = Orders.market("B-1", Side.BUY, "1");
        order.setString(OrdType.FIELD, "Z");                       // not in the FIX 4.4 dictionary

        assertThat(FixTestClient.type(member1.next(order))).isEqualTo(MsgType.REJECT);
    }
}
