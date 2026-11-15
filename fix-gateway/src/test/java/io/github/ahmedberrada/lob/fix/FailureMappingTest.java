package io.github.ahmedberrada.lob.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        when(service.submit(anyString(), any(), any())).thenReturn(CompletableFuture.failedFuture(failure));
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

    /** Places an order whose acknowledgement never arrives, and returns the context attached to it. */
    private Object pendingOrder(String clOrdId) throws Exception {
        when(service.instrument("AAPL")).thenReturn(Optional.of(FixVenue.AAPL));
        when(service.submit(anyString(), any(), any())).thenReturn(new CompletableFuture<>());
        application.fromApp(Orders.limit(clOrdId, Side.BUY, "185.25", "10"), SESSION);
        ArgumentCaptor<Object> context = ArgumentCaptor.forClass(Object.class);
        verify(service, atLeastOnce()).submit(anyString(), any(), context.capture());
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

        when(service.submit(anyString(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new RequestRefusedException(Reason.HALTED, "AAPL")));
        Message refused = cancel("C-1", "B-1");
        assertThat(refused.getInt(CxlRejReason.FIELD)).isEqualTo(CxlRejReason.OTHER);
        assertThat(refused.getString(Text.FIELD)).isEqualTo("HALTED");
        assertThat(refused.getString(OrderID.FIELD)).isEqualTo("AAPL-1");

        when(service.submit(anyString(), any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new UncheckedIOException(new IOException("disk"))));
        Message unknown = cancel("C-2", "B-1");
        assertThat(unknown.getChar(ExecType.FIELD)).isEqualTo(ExecType.PENDING_CANCEL);
        assertThat(unknown.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.PENDING_CANCEL);
        assertThat(unknown.getString(OrigClOrdID.FIELD)).isEqualTo("B-1");
        assertThat(unknown.getString(Text.FIELD)).isEqualTo("OUTCOME_UNKNOWN");
    }

    @Test
    void failedJournalWriteIsPendingNewNotRejected() throws Exception {
        Message report = reportFor(new UncheckedIOException(new IOException("disk")));

        assertThat(report.getChar(ExecType.FIELD)).isEqualTo(ExecType.PENDING_NEW);
        assertThat(report.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.PENDING_NEW);
        assertThat(report.getString(Text.FIELD)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(report.isSetField(OrdRejReason.FIELD)).isFalse();
    }
}
