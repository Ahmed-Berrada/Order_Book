package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Message;
import quickfix.field.BusinessRejectReason;
import quickfix.field.ClOrdID;
import quickfix.field.CopyMsgIndicator;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.MsgType;
import quickfix.field.OnBehalfOfCompID;
import quickfix.field.OrderID;
import quickfix.field.OrigClOrdID;
import quickfix.field.RefMsgType;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.OrderCancelRequest;

class DropCopyTest {

    @TempDir
    Path directory;

    private FixVenue venue;
    private FixTestClient member1;
    private FixTestClient member2;
    private FixTestClient risk;

    @BeforeEach
    void connect() throws Exception {
        venue = new FixVenue(directory, FixVenue.freePort());
        member1 = new FixTestClient("MEMBER1", venue.port, directory).logon();
        member2 = new FixTestClient("MEMBER2", venue.port, directory).logon();
        risk = new FixTestClient("RISK", venue.port, directory).logon();
    }

    @AfterEach
    void disconnect() throws Exception {
        member1.close();
        member2.close();
        risk.close();
        venue.close();
    }

    private static void assertCopyOf(Message copy, Message original, String member) throws Exception {
        assertThat(FixTestClient.type(copy)).isEqualTo(MsgType.EXECUTION_REPORT);
        assertThat(copy.getBoolean(CopyMsgIndicator.FIELD)).isTrue();
        assertThat(copy.getHeader().getString(OnBehalfOfCompID.FIELD)).isEqualTo(member);
        assertThat(field(copy, ExecID.FIELD)).isEqualTo(field(original, ExecID.FIELD));
        assertThat(field(copy, ClOrdID.FIELD)).isEqualTo(field(original, ClOrdID.FIELD));
        assertThat(field(copy, ExecType.FIELD)).isEqualTo(field(original, ExecType.FIELD));
    }

    @Test
    @Rulebook("DC-001")
    void everyExecutionReportOfEveryMemberIsCopied() throws Exception {
        member2.send(Orders.limit("S-1", Side.SELL, "185.30", "10"));
        Message sellNew = member2.next();
        assertCopyOf(risk.next(), sellNew, "MEMBER2");

        member1.send(Orders.market("B-1", Side.BUY, "4"));
        Message buyNew = member1.next();
        Message buyFill = member1.next();
        Message sellFill = member2.next();
        assertCopyOf(risk.next(), buyNew, "MEMBER1");
        assertCopyOf(risk.next(), buyFill, "MEMBER1");
        assertCopyOf(risk.next(), sellFill, "MEMBER2");

        member1.send(Orders.limit("B-2", Side.BUY, "185.255", "1"));                 // refused: copied too
        assertCopyOf(risk.next(), member1.next(), "MEMBER1");
        risk.expectNothing(Duration.ofMillis(200));
    }

    @Test
    @Rulebook("DC-001")
    void cancelRejectsAreNotExecutionReportsAndAreNotCopied() throws Exception {
        OrderCancelRequest cancel = new OrderCancelRequest();
        cancel.setString(ClOrdID.FIELD, "C-1");
        cancel.setString(OrigClOrdID.FIELD, "NOPE");
        cancel.setString(Symbol.FIELD, "AAPL");
        cancel.setChar(Side.FIELD, Side.BUY);
        cancel.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));

        assertThat(FixTestClient.type(member1.next(cancel))).isEqualTo(MsgType.ORDER_CANCEL_REJECT);
        risk.expectNothing(Duration.ofMillis(300));
    }

    @Test
    @Rulebook("DC-002")
    void dropCopySessionsCannotTrade() throws Exception {
        Message reject = risk.next(Orders.limit("R-1", Side.BUY, "185.25", "1"));

        assertThat(FixTestClient.type(reject)).isEqualTo(MsgType.BUSINESS_MESSAGE_REJECT);
        assertThat(reject.getInt(BusinessRejectReason.FIELD)).isEqualTo(BusinessRejectReason.NOT_AUTHORIZED);
        assertThat(reject.getString(RefMsgType.FIELD)).isEqualTo(MsgType.NEW_ORDER_SINGLE);
        member1.send(Orders.limit("B-1", Side.BUY, "185.25", "1"));
        assertThat(field(member1.next(), OrderID.FIELD)).isEqualTo("AAPL-1");      // nothing reached the engine
    }

    @Test
    void sessionsMustBeDistinct() {
        Path store = directory.resolve("store");
        assertThatIllegalArgumentException().isThrownBy(() ->
                new FixGatewayConfig("127.0.0.1", 9_000, "LOB", List.of("M"), List.of("M"), store));
    }
}
