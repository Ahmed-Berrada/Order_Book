package io.github.ahmedberrada.lob.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import io.github.ahmedberrada.lob.service.RequestRefusedException.Reason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.ExecType;
import quickfix.field.OrdRejReason;
import quickfix.field.OrdStatus;
import quickfix.field.OrderID;
import quickfix.field.Side;
import quickfix.field.Text;

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

    @Test
    void failedJournalWriteIsPendingNewNotRejected() throws Exception {
        Message report = reportFor(new UncheckedIOException(new IOException("disk")));

        assertThat(report.getChar(ExecType.FIELD)).isEqualTo(ExecType.PENDING_NEW);
        assertThat(report.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.PENDING_NEW);
        assertThat(report.getString(Text.FIELD)).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(report.isSetField(OrdRejReason.FIELD)).isFalse();
    }
}
