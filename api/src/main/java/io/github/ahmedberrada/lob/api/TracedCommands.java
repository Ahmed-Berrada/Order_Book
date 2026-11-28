package io.github.ahmedberrada.lob.api;

import io.github.ahmedberrada.lob.core.CancelRejected;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Event;
import io.github.ahmedberrada.lob.core.MemberCommandRejected;
import io.github.ahmedberrada.lob.core.OrderRejected;
import io.github.ahmedberrada.lob.core.PhaseChangeRejected;
import io.github.ahmedberrada.lob.core.ReplaceRejected;
import io.github.ahmedberrada.lob.journal.EventBatch;
import io.github.ahmedberrada.lob.service.MatchingService;
import io.github.ahmedberrada.lob.service.RequestRefusedException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Submits the REST API's commands, each observed from submission to its acknowledgement (ADR-0023): one span, a
 * child of the HTTP request's, and the {@code lob.request} timer. Both cover what a client waits for, queueing and
 * the journal's flush included, which {@code lob.command.duration} does not.
 *
 * <p>Low-cardinality keys (metric tags and span attributes): {@code symbol} (as on the other {@code lob} meters;
 * {@code unknown} for a symbol that is not listed), {@code lob.command} and
 * {@code lob.outcome}: {@code accepted}, {@code rejected} (by the engine; journaled), {@code refused} (before the
 * journal) or {@code unknown} (a failed write). High-cardinality keys (span only): {@code lob.member},
 * {@code lob.command.sequence}, the command's position in the instrument's journal, and {@code lob.refusal}.
 */
@Component
class TracedCommands {

    static final String NAME = "lob.request";

    private final MatchingService service;
    private final ObservationRegistry registry;

    TracedCommands(MatchingService service, ObjectProvider<ObservationRegistry> registry) {
        this.service = service;
        this.registry = registry.getIfAvailable(() -> ObservationRegistry.NOOP);
    }

    CompletableFuture<EventBatch> submit(String symbol, Command command) {
        return submit(symbol, command, 0);
    }

    CompletableFuture<EventBatch> submit(String symbol, Command command, long memberId) {
        String type = command.getClass().getSimpleName();
        Observation observation = Observation.createNotStarted(NAME, registry)
                .contextualName("lob " + type)
                // a symbol from the URL that is not listed is not a tag value: callers could make series without end
                .lowCardinalityKeyValue("symbol", service.instrument(symbol).isPresent() ? symbol : "unknown")
                .lowCardinalityKeyValue("lob.command", type)
                .highCardinalityKeyValue("lob.member", Long.toString(memberId))
                .start();
        CompletableFuture<EventBatch> result;
        try (Observation.Scope scope = observation.openScope()) {
            result = service.submit(symbol, command, memberId, null);
        } catch (RuntimeException e) {
            observation.lowCardinalityKeyValue("lob.outcome", "refused").error(e).stop();
            throw e;
        }
        return result.whenComplete((batch, failure) -> {
            if (failure == null) {
                observation.lowCardinalityKeyValue("lob.outcome", rejected(batch) ? "rejected" : "accepted")
                        .highCardinalityKeyValue("lob.command.sequence", Long.toString(batch.commandSequence()));
            } else {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                if (cause instanceof RequestRefusedException refused) {
                    observation.lowCardinalityKeyValue("lob.outcome", "refused")
                            .highCardinalityKeyValue("lob.refusal", refused.reason().name());
                } else {
                    observation.lowCardinalityKeyValue("lob.outcome", "unknown").error(cause);
                }
            }
            observation.stop();
        });
    }

    private static boolean rejected(EventBatch batch) {
        if (batch.events().isEmpty()) {
            return false;
        }
        Event first = batch.events().getFirst();
        return first instanceof OrderRejected || first instanceof ReplaceRejected || first instanceof CancelRejected
                || first instanceof MemberCommandRejected || first instanceof PhaseChangeRejected;
    }
}
