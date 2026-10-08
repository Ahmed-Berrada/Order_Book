package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Message;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.Side;

/** The resting side of a trade is told too, on its own session, whoever caused the trade. */
class MakerFillTest {

    @TempDir
    Path directory;

    private FixVenue venue;
    private FixTestClient taker;
    private FixTestClient maker;

    @BeforeEach
    void connect() throws Exception {
        venue = new FixVenue(directory, FixVenue.freePort());
        taker = new FixTestClient("MEMBER1", venue.port, directory).logon();
        maker = new FixTestClient("MEMBER2", venue.port, directory).logon();
        maker.send(Orders.limit("S-1", Side.SELL, "185.30", "10"));
        assertThat(field(maker.next(), OrderID.FIELD)).isEqualTo("AAPL-1");
    }

    @AfterEach
    void disconnect() throws Exception {
        taker.close();
        maker.close();
        venue.close();
    }

    @Test
    void makerGetsAFillReportOnItsOwnSession() throws Exception {
        taker.send(Orders.limit("B-1", Side.BUY, "185.30", "4"));
        assertThat(field(taker.next(), ExecType.FIELD)).isEqualTo("0");
        Message takerFill = taker.next();

        Message makerFill = maker.next();
        assertThat(field(makerFill, ClOrdID.FIELD)).isEqualTo("S-1");
        assertThat(field(makerFill, OrderID.FIELD)).isEqualTo("AAPL-1");
        assertThat(field(makerFill, ExecType.FIELD)).isEqualTo("F");
        assertThat(field(makerFill, OrdStatus.FIELD)).isEqualTo("1");
        assertThat(field(makerFill, LastPx.FIELD)).isEqualTo("185.30");
        assertThat(field(makerFill, LastQty.FIELD)).isEqualTo("4");
        assertThat(field(makerFill, CumQty.FIELD)).isEqualTo("4");
        assertThat(field(makerFill, LeavesQty.FIELD)).isEqualTo("6");
        assertThat(field(makerFill, AvgPx.FIELD)).isEqualTo("185.3");
        assertThat(field(makerFill, ExecID.FIELD)).isEqualTo(field(takerFill, ExecID.FIELD) + "-M");
        maker.expectNothing(Duration.ofMillis(200));
    }

    @Test
    void fillsCausedOutsideFixAreReportedToo() throws Exception {
        venue.service.submit("AAPL", new MarketOrder(io.github.ahmedberrada.lob.core.Side.BUY, 10)).join();   // e.g. REST

        Message fill = maker.next();
        assertThat(field(fill, OrdStatus.FIELD)).isEqualTo("2");
        assertThat(field(fill, LeavesQty.FIELD)).isEqualTo("0");
    }

    @Test
    void cancelFromOutsideFixIsReportedToTheOwner() throws Exception {
        venue.service.submit("AAPL", new CancelOrder(1)).join();

        Message cancelled = maker.next();
        assertThat(field(cancelled, ExecType.FIELD)).isEqualTo("4");
        assertThat(field(cancelled, OrdStatus.FIELD)).isEqualTo("4");
        assertThat(field(cancelled, LeavesQty.FIELD)).isEqualTo("0");
    }

    @Test
    void fillsWhileDisconnectedAreDeliveredAfterLogon() throws Exception {
        maker.disconnect();
        taker.send(Orders.market("B-1", Side.BUY, "3"));
        taker.next();
        taker.next();

        maker.logon();                                   // the gap is detected and the venue resends

        Message fill = maker.next();
        assertThat(field(fill, ClOrdID.FIELD)).isEqualTo("S-1");
        assertThat(field(fill, LastQty.FIELD)).isEqualTo("3");
    }
}
