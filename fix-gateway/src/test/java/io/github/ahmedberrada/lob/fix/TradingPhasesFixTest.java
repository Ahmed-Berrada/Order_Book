package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.TradingPhase;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Message;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrigClOrdID;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.fix44.OrderCancelRequest;

/** Trading phases over FIX: orders in a call phase, the uncross, and a closed venue. */
class TradingPhasesFixTest {

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

    private static Message cancel(String clOrdId, String origClOrdId, char side) {
        OrderCancelRequest request = new OrderCancelRequest();
        request.setString(ClOrdID.FIELD, clOrdId);
        request.setString(OrigClOrdID.FIELD, origClOrdId);
        request.setString(Symbol.FIELD, "AAPL");
        request.setChar(Side.FIELD, side);
        request.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        return request;
    }

    private void phase(TradingPhase phase) {
        venue.service.submit("AAPL", new SetPhase(phase)).join();
    }

    @Test
    @Rulebook({"TP-005", "AU-002"})
    void anAuctionTradesAtOnePriceAndBothMembersAreToldOfTheirFill() throws Exception {
        phase(TradingPhase.CALL);
        member1.send(Orders.limit("S-1", Side.SELL, "185.00", "5"));
        assertThat(field(member1.next(), ExecType.FIELD)).isEqualTo("0");
        member2.send(Orders.limit("B-1", Side.BUY, "185.50", "5"));
        assertThat(field(member2.next(), ExecType.FIELD)).isEqualTo("0");      // accepted, nothing traded
        assertThat(venue.service.restingQuantity("AAPL", 1).join()).isEqualTo(5);

        phase(TradingPhase.CONTINUOUS);

        Message taker = member2.next();                    // the later order is the taker (AU-002)
        assertThat(field(taker, ClOrdID.FIELD)).isEqualTo("B-1");
        assertThat(field(taker, ExecType.FIELD)).isEqualTo("F");
        assertThat(field(taker, OrdStatus.FIELD)).isEqualTo("2");
        assertThat(field(taker, LastPx.FIELD)).isEqualTo("185.50");
        assertThat(field(taker, LastQty.FIELD)).isEqualTo("5");
        assertThat(field(taker, CumQty.FIELD)).isEqualTo("5");
        assertThat(field(taker, LeavesQty.FIELD)).isEqualTo("0");
        Message maker = member1.next();
        assertThat(field(maker, ClOrdID.FIELD)).isEqualTo("S-1");
        assertThat(field(maker, ExecType.FIELD)).isEqualTo("F");
        assertThat(field(maker, LastPx.FIELD)).isEqualTo("185.50");
        assertThat(field(maker, OrdStatus.FIELD)).isEqualTo("2");
    }

    @Test
    @Rulebook("TP-005")
    void aCallPhaseRejectsAMarketOrderAsExchangeClosed() throws Exception {
        phase(TradingPhase.CALL);

        member1.send(Orders.market("M-1", Side.BUY, "1"));

        Message reject = member1.next();
        assertThat(field(reject, ExecType.FIELD)).isEqualTo("8");
        assertThat(field(reject, OrdRejReason.FIELD)).isEqualTo("2");
        assertThat(field(reject, Text.FIELD)).isEqualTo("NOT_ALLOWED_IN_PHASE");
    }

    @Test
    @Rulebook("TP-004")
    void aClosedVenueRejectsNewOrdersButAnOrderCanStillBeCancelled() throws Exception {
        member1.send(Orders.limit("S-1", Side.SELL, "185.00", "5"));
        member1.next();
        phase(TradingPhase.CLOSED);

        member1.send(Orders.limit("S-2", Side.SELL, "185.00", "5"));
        Message reject = member1.next();
        assertThat(field(reject, ExecType.FIELD)).isEqualTo("8");
        assertThat(field(reject, OrdRejReason.FIELD)).isEqualTo("2");

        member1.send(cancel("C-1", "S-1", Side.SELL));
        assertThat(field(member1.next(), ExecType.FIELD)).isEqualTo("4");
    }
}
