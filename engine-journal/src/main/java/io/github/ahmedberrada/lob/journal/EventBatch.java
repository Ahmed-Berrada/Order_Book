package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Event;
import java.util.List;

/**
 * The events produced by one command: one record of the event journal, and what
 * {@link JournaledEngine#process} returns. All events of a command share its timestamp (RS-006).
 *
 * @param commandSequence sequence number of the command in the command journal
 * @param timestampMicros the command's ingress time, microseconds since the Unix epoch (UTC)
 */
public record EventBatch(long commandSequence, long timestampMicros, List<Event> events) {

    public EventBatch {
        events = List.copyOf(events);
    }
}
