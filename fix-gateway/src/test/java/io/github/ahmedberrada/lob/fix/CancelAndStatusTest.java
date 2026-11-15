package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Message;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecType;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrigClOrdID;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.OrderStatusRequest;

class CancelAndStatusTest {

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
        member1.send(Orders.limit("B-1", Side.BUY, "185.25", "10"));
        assertThat(field(member1.next(), OrderID.FIELD)).isEqualTo("AAPL-1");
    }

    @AfterEach
    void disconnect() throws Exception {
        member1.close();
        member2.close();
        venue.close();
    }

    private static Message cancel(String clOrdId, String origClOrdId, char side) {
        OrderCancelRequest request = new OrderCancelRequest();
        request.setString(ClOrdID.FIELD, clOrdId);
        request.setString(OrigClOrdID.FIELD, origClOrdId);
        request.setString(Symbol.FIELD, "AAPL");
        request.setChar(Side.FIELD, side);
        request.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        return request;
    }

    private static Message status(String clOrdId) {
        OrderStatusRequest request = new OrderStatusRequest();
        request.setString(ClOrdID.FIELD, clOrdId);
        request.setString(Symbol.FIELD, "AAPL");
        request.setChar(Side.FIELD, Side.BUY);
        return request;
    }

    private static void assertCancelReject(Message reject, int reason) throws Exception {
        assertThat(FixTestClient.type(reject)).isEqualTo(MsgType.ORDER_CANCEL_REJECT);
        assertThat(reject.getInt(CxlRejReason.FIELD)).isEqualTo(reason);
        assertThat(reject.getChar(CxlRejResponseTo.FIELD)).isEqualTo(CxlRejResponseTo.ORDER_CANCEL_REQUEST);
    }

    @Test
    void cancelByOrigClOrdIdThenTooLate() throws Exception {
        Message confirmed = member1.next(cancel("C-1", "B-1", Side.BUY));
        assertThat(field(confirmed, ExecType.FIELD)).isEqualTo("4");
        assertThat(field(confirmed, OrdStatus.FIELD)).isEqualTo("4");
        assertThat(field(confirmed, ClOrdID.FIELD)).isEqualTo("C-1");
        assertThat(field(confirmed, OrigClOrdID.FIELD)).isEqualTo("B-1");
        assertThat(field(confirmed, OrderID.FIELD)).isEqualTo("AAPL-1");
        assertThat(field(confirmed, LeavesQty.FIELD)).isEqualTo("0");

        Message tooLate = member1.next(cancel("C-2", "B-1", Side.BUY));
        assertCancelReject(tooLate, CxlRejReason.TOO_LATE_TO_CANCEL);
        assertThat(field(tooLate, OrdStatus.FIELD)).isEqualTo("4");
    }

    @Test
    void membersCannotCancelEachOthersOrders() throws Exception {
        assertCancelReject(member2.next(cancel("C-1", "B-1", Side.BUY)), CxlRejReason.UNKNOWN_ORDER);
        assertCancelReject(member1.next(cancel("C-1", "NOPE", Side.BUY)), CxlRejReason.UNKNOWN_ORDER);
        assertCancelReject(member1.next(cancel("C-2", "B-1", Side.SELL)), CxlRejReason.UNKNOWN_ORDER);    // wrong side
        assertCancelReject(member1.next(cancel("C-2", "B-1", Side.BUY)), CxlRejReason.DUPLICATE_CLORDID_RECEIVED);

        // the order is still there
        Message report = member1.next(status("B-1"));
        assertThat(field(report, OrdStatus.FIELD)).isEqualTo("0");
        assertThat(field(report, LeavesQty.FIELD)).isEqualTo("10");
    }

    @Test
    void statusRequestReportsTheCurrentState() throws Exception {
        member2.send(Orders.market("S-1", Side.SELL, "4"));
        member2.next();

        Message partial = member1.next();                 // maker fill on member1
        assertThat(field(partial, ExecType.FIELD)).isEqualTo("F");

        Message report = member1.next(status("B-1"));
        assertThat(field(report, ExecType.FIELD)).isEqualTo("I");
        assertThat(field(report, OrdStatus.FIELD)).isEqualTo("1");
        assertThat(field(report, CumQty.FIELD)).isEqualTo("4");
        assertThat(field(report, LeavesQty.FIELD)).isEqualTo("6");
        assertThat(field(report, OrderID.FIELD)).isEqualTo("AAPL-1");
    }

    @Test
    void statusOfUnknownOrRefusedOrders() throws Exception {
        Message unknown = member1.next(status("NOPE"));
        assertThat(field(unknown, ExecType.FIELD)).isEqualTo("I");
        assertThat(field(unknown, OrdStatus.FIELD)).isEqualTo("8");
        assertThat(field(unknown, OrdRejReason.FIELD)).isEqualTo("5");
        assertThat(field(unknown, OrderID.FIELD)).isEqualTo("NONE");

        member1.next(Orders.limit("B-2", Side.BUY, "185.255", "1"));       // refused: off tick
        Message refused = member1.next(status("B-2"));
        assertThat(field(refused, OrdStatus.FIELD)).isEqualTo("8");
        assertThat(field(refused, Text.FIELD)).isEqualTo("REFUSED");
        member1.expectNothing(Duration.ofMillis(100));
    }
}
