package io.github.ahmedberrada.lob.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.ahmedberrada.lob.core.OrderAccepted;
import io.github.ahmedberrada.lob.core.OrderRested;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.ClOrdID;
import quickfix.field.CxlRejReason;
import quickfix.field.ExecType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.OrigClOrdID;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TransactTime;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;
import quickfix.fix44.OrderStatusRequest;

/** Failures a healthy venue cannot produce on demand, mapped with a stubbed service. */
class FailureMappingTest {

    private static final SessionID SESSION = new SessionID(FixGateway.BEGIN_STRING, "LOB", "MEMBER1");

    private final MatchingService service = mock(MatchingService.class);
    private final List<Message> sent = new ArrayList<>();
    private final FixApplication application = new FixApplication(service, (message, session) -> sent.add(message));

    private Message reportFor(Throwable failure) throws Exception {
        when(service.instrument("AAPL")).thenReturn(Optional.of(FixVenue.AAPL));
        when(service.submit(anyString(), any(), anyLong(), any())).thenReturn(CompletableFuture.failedFuture(failure));
        sent.clear();
        application.fromApp(Orders.market("B-" + System.nanoTime(), Side.BUY, "1"), SESSION);
        assertThat(sent).hasSize(1);
        return sent.getFirst();
    }

    @Test
    @Rulebook({"OE-005", "OE-006", "OE-007"})
    void refusalsAreRejectionsWithoutOrder() throws Exception {
        Message overloaded = reportFor(new RequestRefusedException(Reason.OVERLOADED, "AAPL"));
        assertThat(overloaded.getString(OrdRejReason.FIELD)).isEqualTo("99");
        assertThat(overloaded.getString(Text.FIELD)).isEqualTo("OVERLOADED");
        assertThat(overloaded.getString(OrderID.FIELD)).isEqualTo("NONE");

        assertThat(reportFor(new RequestRefusedException(Reason.HALTED, "AAPL")).getString(Text.FIELD)).isEqualTo("HALTED");
        assertThat(reportFor(new RequestRefusedException(Reason.STOPPED, "AAPL")).getString(OrdRejReason.FIELD)).isEqualTo("2");
        assertThat(reportFor(new CompletionException(new RequestRefusedException(Reason.UNKNOWN_INSTRUMENT, "AAPL")))
                .getString(OrdRejReason.FIELD)).isEqualTo("1");
    }

    @Test
    @Rulebook({"OE-008", "OE-009"})
    void throttledOrdersAreRejectionsWithTheReason() throws Exception {
        Message throttled = reportFor(new RequestRefusedException(Reason.THROTTLED, "AAPL"));
        assertThat(throttled.getString(OrdRejReason.FIELD)).isEqualTo("99");
        assertThat(throttled.getString(Text.FIELD)).isEqualTo("THROTTLED");
        Message ratio = reportFor(new RequestRefusedException(Reason.ORDER_TO_TRADE_LIMIT, "AAPL"));
        assertThat(ratio.getString(OrdRejReason.FIELD)).isEqualTo("99");
        assertThat(ratio.getString(Text.FIELD)).isEqualTo("ORDER_TO_TRADE_LIMIT");
    }

    /** Places an order whose acknowledgement never arrives, and returns the context attached to it. */
    private Object pendingOrder(String clOrdId) throws Exception {
        when(service.instrument("AAPL")).thenReturn(Optional.of(FixVenue.AAPL));
        when(service.submit(anyString(), any(), anyLong(), any())).thenReturn(new CompletableFuture<>());
        application.fromApp(Orders.limit(clOrdId, Side.BUY, "185.25", "10"), SESSION);
        ArgumentCaptor<Object> context = ArgumentCaptor.forClass(Object.class);
        verify(service, atLeastOnce()).submit(anyString(), any(), anyLong(), context.capture());
        return context.getValue();
    }

    /** Places an order and delivers its acknowledgement, as the instrument thread would. */
    private void acknowledgedOrder(String clOrdId) throws Exception {
        Object context = pendingOrder(clOrdId);
        application.onEvents("AAPL", new EventBatch(1, 1, List.of(new OrderAccepted(1, 1),
                new OrderRested(2, 1, io.github.ahmedberrada.lob.core.Side.BUY, 18_525, 10))), context);
        sent.clear();
    }

    private Message cancel(String clOrdId, String origClOrdId) throws Exception {
        OrderCancelRequest request = new OrderCancelRequest();
        request.setString(ClOrdID.FIELD, clOrdId);
        request.setString(OrigClOrdID.FIELD, origClOrdId);
        request.setString(Symbol.FIELD, "AAPL");
        request.setChar(Side.FIELD, Side.BUY);
        request.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        sent.clear();
        application.fromApp(request, SESSION);
        assertThat(sent).hasSize(1);
        return sent.getFirst();
    }

    @Test
    void ordersAwaitingTheirAcknowledgementCannotBeCancelledYet() throws Exception {
        pendingOrder("B-1");

        Message reject = cancel("C-1", "B-1");
        assertThat(reject.getInt(CxlRejReason.FIELD)).isEqualTo(3);

        OrderStatusRequest status = new OrderStatusRequest();
        status.setString(ClOrdID.FIELD, "B-1");
        status.setString(Symbol.FIELD, "AAPL");
        status.setChar(Side.FIELD, Side.BUY);
        sent.clear();
        application.fromApp(status, SESSION);
        assertThat(sent.getFirst().getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.PENDING_NEW);
        assertThat(sent.getFirst().getString(Text.FIELD)).isEqualTo("PENDING");
    }

    @Test
    void refusedCancelIsACancelRejectAndUnknownCancelIsPending() throws Exception {
        acknowledgedOrder("B-1");

        when(service.submit(anyString(), any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RequestRefusedException(Reason.HALTED, "AAPL")));
        Message refused = cancel("C-1", "B-1");
        assertThat(refused.getInt(CxlRejReason.FIELD)).isEqualTo(CxlRejReason.OTHER);
        assertThat(refused.getString(Text.FIELD)).isEqualTo("HALTED");
        assertThat(refused.getString(OrderID.FIELD)).isEqualTo("AAPL-1");

        when(service.submit(anyString(), any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(new UncheckedIOException(new IOException("disk"))));
        Message unknown = cancel("C-2", "B-1");
        assertThat(unknown.getChar(ExecType.FIELD)).isEqualTo(ExecType.PENDING_CANCEL);
        assertThat(unknown.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.PENDING_CANCEL);
        assertThat(unknown.getString(OrigClOrdID.FIELD)).isEqualTo("B-1");
        assertThat(unknown.getString(Text.FIELD)).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    @Rulebook({"OE-008", "OE-009"})
    void aThrottledReplaceIsACancelRejectWithTheReason() throws Exception {
        acknowledgedOrder("B-1");
        for (Reason reason : List.of(Reason.THROTTLED, Reason.ORDER_TO_TRADE_LIMIT)) {
            when(service.submit(anyString(), any(), anyLong(), any()))
                    .thenReturn(CompletableFuture.failedFuture(new RequestRefusedException(reason, "AAPL")));
            OrderCancelReplaceRequest request = new OrderCancelReplaceRequest();
            request.setString(ClOrdID.FIELD, "R-" + reason);
            request.setString(OrigClOrdID.FIELD, "B-1");
            request.setString(Symbol.FIELD, "AAPL");
            request.setChar(Side.FIELD, Side.BUY);
            request.setChar(OrdType.FIELD, OrdType.LIMIT);
            request.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
            request.setDecimal(Price.FIELD, new BigDecimal("185.30"));
            request.setDecimal(OrderQty.FIELD, new BigDecimal("10"));
            sent.clear();
            application.fromApp(request, SESSION);
            assertThat(sent).hasSize(1);
            assertThat(sent.getFirst().getInt(CxlRejReason.FIELD)).isEqualTo(CxlRejReason.OTHER);
            assertThat(sent.getFirst().getString(Text.FIELD)).isEqualTo(reason.name());
        }
    }

    @Test
    void failedJournalWriteIsPendingNewNotRejected() throws Exception {
        Message report = reportFor(new UncheckedIOException(new IOException("disk")));

        assertThat(report.getChar(ExecType.FIELD)).isEqualTo(ExecType.PENDING_NEW);
        assertThat(report.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.PENDING_NEW);
        assertThat(report.getString(Text.FIELD)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(report.isSetField(OrdRejReason.FIELD)).isFalse();
    }

    private Message massCancelAll(String clOrdId) throws Exception {
        quickfix.fix44.OrderMassCancelRequest request = new quickfix.fix44.OrderMassCancelRequest();
        request.setString(ClOrdID.FIELD, clOrdId);
        request.setChar(quickfix.field.MassCancelRequestType.FIELD, quickfix.field.MassCancelRequestType.CANCEL_ALL_ORDERS);
        request.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        sent.clear();
        application.fromApp(request, SESSION);
        assertThat(sent).hasSize(1);
        return sent.getFirst();
    }

    @Test
    @Rulebook("RK-006")
    void aMassCancelWhoseOutcomeIsUnknownIsNeverReportedAsRejected() throws Exception {
        io.github.ahmedberrada.lob.service.InstrumentConfig msft = new io.github.ahmedberrada.lob.service.InstrumentConfig(
                "MSFT", new BigDecimal("0.01"), 1_000, new BigDecimal("1000.00"));
        when(service.instruments()).thenReturn(List.of(FixVenue.AAPL, msft));
        when(service.submit(org.mockito.ArgumentMatchers.eq("AAPL"), any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(new UncheckedIOException(new IOException("disk"))));
        when(service.submit(org.mockito.ArgumentMatchers.eq("MSFT"), any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RequestRefusedException(Reason.HALTED, "MSFT")));
        Message report = massCancelAll("M-1");
        assertThat(report.getChar(quickfix.field.MassCancelResponse.FIELD)).isEqualTo('7');
        assertThat(report.getInt(quickfix.field.TotalAffectedOrders.FIELD)).isZero();
        assertThat(report.getString(Text.FIELD)).isEqualTo("NOT_CANCELLED_IN MSFT HALTED; OUTCOME_UNKNOWN_IN AAPL");

        when(service.submit(org.mockito.ArgumentMatchers.eq("AAPL"), any(), anyLong(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RequestRefusedException(Reason.STOPPED, "AAPL")));
        Message rejected = massCancelAll("M-2");
        assertThat(rejected.getChar(quickfix.field.MassCancelResponse.FIELD)).as("every instrument refused").isEqualTo('0');
        assertThat(rejected.getString(Text.FIELD)).isEqualTo("AAPL STOPPED, MSFT HALTED");

        when(service.instruments()).thenReturn(List.of());
        Message none = massCancelAll("M-3");
        assertThat(none.getChar(quickfix.field.MassCancelResponse.FIELD)).as("no instrument: nothing to cancel").isEqualTo('7');
        assertThat(none.getInt(quickfix.field.TotalAffectedOrders.FIELD)).isZero();
        assertThat(none.isSetField(Text.FIELD)).isFalse();
    }

    @Test
    @Rulebook({"FS-001", "FS-002"})
    void aHaltedInstrumentIsHaltedAndAnInterruptionIsNamed() throws Exception {
        when(service.instruments()).thenReturn(List.of(FixVenue.AAPL));
        when(service.isHalted("AAPL")).thenReturn(true);
        sent.clear();
        application.onLogon(SESSION);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getInt(quickfix.field.TradSesStatus.FIELD)).isEqualTo(quickfix.field.TradSesStatus.HALTED);

        application.onLogout(SESSION);
        when(service.isHalted("AAPL")).thenReturn(false);
        when(service.book("AAPL", 0)).thenReturn(CompletableFuture.failedFuture(
                new RequestRefusedException(Reason.HALTED, "AAPL")));
        sent.clear();
        application.onLogon(SESSION);
        assertThat(sent.getFirst().getString(Text.FIELD)).isEqualTo("HALTED");

        application.onLogout(SESSION);
        when(service.book("AAPL", 0)).thenReturn(CompletableFuture.failedFuture(
                new RequestRefusedException(Reason.OVERLOADED, "AAPL")));
        sent.clear();
        application.onLogon(SESSION);
        assertThat(sent.getFirst().getInt(quickfix.field.TradSesStatus.FIELD)).as("busy is not halted")
                .isEqualTo(quickfix.field.TradSesStatus.UNKNOWN);
        assertThat(sent.getFirst().getString(Text.FIELD)).isEqualTo("OVERLOADED");

        sent.clear();
        application.onEvents("AAPL", new EventBatch(5, 1, List.of(
                new io.github.ahmedberrada.lob.core.VolatilityInterruption(1, 10_000, 10_600),
                new io.github.ahmedberrada.lob.core.PhaseChanged(2, io.github.ahmedberrada.lob.core.TradingPhase.CONTINUOUS,
                        io.github.ahmedberrada.lob.core.TradingPhase.CALL))), null);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getInt(quickfix.field.TradSesStatus.FIELD)).isEqualTo(quickfix.field.TradSesStatus.PRE_OPEN);
        assertThat(sent.getFirst().getString(Text.FIELD)).isEqualTo("VOLATILITY_INTERRUPTION");

        application.onLogout(SESSION);
        sent.clear();
        application.onEvents("AAPL", new EventBatch(6, 1, List.of(new io.github.ahmedberrada.lob.core.PhaseChanged(3,
                io.github.ahmedberrada.lob.core.TradingPhase.CALL, io.github.ahmedberrada.lob.core.TradingPhase.CONTINUOUS))), null);
        assertThat(sent).as("only sessions logged on").isEmpty();
    }

    @Test
    @Rulebook("FS-001")
    void aStatusReadBeforeAPhaseChangeNeverOverwritesIt() throws Exception {
        when(service.instruments()).thenReturn(List.of(FixVenue.AAPL));
        when(service.instrument("AAPL")).thenReturn(Optional.of(FixVenue.AAPL));
        CompletableFuture<io.github.ahmedberrada.lob.service.BookSnapshot> read = new CompletableFuture<>();
        when(service.book("AAPL", 0)).thenReturn(read);
        sent.clear();
        application.onLogon(SESSION);                                  // reads the phase: answered later
        application.onEvents("AAPL", new EventBatch(8, 1, List.of(new io.github.ahmedberrada.lob.core.PhaseChanged(3,
                io.github.ahmedberrada.lob.core.TradingPhase.CONTINUOUS, io.github.ahmedberrada.lob.core.TradingPhase.CALL))), null);
        read.complete(new io.github.ahmedberrada.lob.service.BookSnapshot(7,
                io.github.ahmedberrada.lob.core.TradingPhase.CONTINUOUS, List.of(), List.of()));
        assertThat(sent).as("the older status read at command 7 is dropped").hasSize(1);
        assertThat(sent.getFirst().getInt(quickfix.field.TradSesStatus.FIELD)).isEqualTo(quickfix.field.TradSesStatus.PRE_OPEN);

        CompletableFuture<io.github.ahmedberrada.lob.service.BookSnapshot> stale = new CompletableFuture<>();
        when(service.book("AAPL", 0)).thenReturn(stale);
        quickfix.fix44.TradingSessionStatusRequest request = new quickfix.fix44.TradingSessionStatusRequest();
        request.setString(quickfix.field.TradSesReqID.FIELD, "R-1");
        request.setChar(quickfix.field.SubscriptionRequestType.FIELD, '0');
        request.setString(quickfix.field.TradingSessionID.FIELD, "AAPL");
        sent.clear();
        application.fromApp(request, SESSION);
        stale.complete(new io.github.ahmedberrada.lob.service.BookSnapshot(7,
                io.github.ahmedberrada.lob.core.TradingPhase.CONTINUOUS, List.of(), List.of()));
        assertThat(sent).as("a request is answered, with the newer status").hasSize(1);
        assertThat(sent.getFirst().getInt(quickfix.field.TradSesStatus.FIELD)).isEqualTo(quickfix.field.TradSesStatus.PRE_OPEN);
        assertThat(sent.getFirst().getString(quickfix.field.TradSesReqID.FIELD)).isEqualTo("R-1");
    }
}
