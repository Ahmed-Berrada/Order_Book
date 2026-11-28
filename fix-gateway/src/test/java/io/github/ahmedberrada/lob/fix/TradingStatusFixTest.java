package io.github.ahmedberrada.lob.fix;

import static io.github.ahmedberrada.lob.fix.Orders.field;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.TradingPhase;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quickfix.Message;
import quickfix.field.SubscriptionRequestType;
import quickfix.field.TradSesReqID;
import quickfix.field.TradSesStatus;
import quickfix.field.TradSesStatusRejReason;
import quickfix.field.TradingSessionID;
import quickfix.field.UnsolicitedIndicator;
import quickfix.fix44.TradingSessionStatusRequest;

/** Each instrument's trading status over FIX: at logon, on a phase change and on request (FS-001, FS-002). */
class TradingStatusFixTest {

    @TempDir
    Path directory;

    private FixVenue venue;
    private FixTestClient member1;
    private FixTestClient risk;

    @BeforeEach
    void connect() throws Exception {
        venue = new FixVenue(directory, FixVenue.freePort());
        member1 = new FixTestClient("MEMBER1", venue.port, directory).logon();
        risk = new FixTestClient("RISK", venue.port, directory).logon();
    }

    @AfterEach
    void disconnect() throws Exception {
        member1.close();
        risk.close();
        venue.close();
    }

    private static void assertStatus(Message status, String symbol, int value, boolean unsolicited) throws Exception {
        assertThat(field(status, TradingSessionID.FIELD)).isEqualTo(symbol);
        assertThat(status.getInt(TradSesStatus.FIELD)).isEqualTo(value);
        assertThat(status.getBoolean(UnsolicitedIndicator.FIELD)).isEqualTo(unsolicited);
    }

    private static Message request(String reqId, String symbol) {
        TradingSessionStatusRequest request = new TradingSessionStatusRequest();
        request.setString(TradSesReqID.FIELD, reqId);
        request.setChar(SubscriptionRequestType.FIELD, SubscriptionRequestType.SNAPSHOT);
        if (symbol != null) {
            request.setString(TradingSessionID.FIELD, symbol);
        }
        return request;
    }

    @Test
    @Rulebook({"FS-001", "FS-002"})
    void sessionsAreToldTheStatusAtLogonAndEveryPhaseChange() throws Exception {
        assertStatus(member1.nextStatus(), "AAPL", TradSesStatus.OPEN, true);
        assertStatus(risk.nextStatus(), "AAPL", TradSesStatus.OPEN, true);       // drop copies too

        venue.service.submit("AAPL", new SetPhase(TradingPhase.CALL)).join();
        assertStatus(member1.nextStatus(), "AAPL", TradSesStatus.PRE_OPEN, true);
        assertStatus(risk.nextStatus(), "AAPL", TradSesStatus.PRE_OPEN, true);

        venue.service.submit("AAPL", new SetPhase(TradingPhase.CLOSED)).join();
        assertStatus(member1.nextStatus(), "AAPL", TradSesStatus.CLOSED, true);

        venue.service.submit("AAPL", new SetPhase(TradingPhase.CLOSED)).join();   // refused: no change
        member1.expectNoStatus(Duration.ofMillis(300));
    }

    @Test
    @Rulebook("FS-002")
    void aRequestIsAnsweredForOneInstrumentOrAll() throws Exception {
        member1.nextStatus();
        venue.service.submit("AAPL", new SetPhase(TradingPhase.CALL)).join();
        member1.nextStatus();

        member1.send(request("R-1", "AAPL"));
        Message one = member1.nextStatus();
        assertStatus(one, "AAPL", TradSesStatus.PRE_OPEN, false);
        assertThat(field(one, TradSesReqID.FIELD)).isEqualTo("R-1");

        member1.send(request("R-2", null));
        assertStatus(member1.nextStatus(), "AAPL", TradSesStatus.PRE_OPEN, false);

        member1.send(request("R-3", "IBM"));
        Message unknown = member1.nextStatus();
        assertThat(unknown.getInt(TradSesStatus.FIELD)).isEqualTo(TradSesStatus.REQUEST_REJECTED);
        assertThat(unknown.getInt(TradSesStatusRejReason.FIELD))
                .isEqualTo(TradSesStatusRejReason.UNKNOWN_OR_INVALID_TRADINGSESSIONID);
    }
}
