package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Command;
import java.util.Objects;

/**
 * One record of the command journal.
 *
 * @param sequence        position in the journal: 1, 2, 3… with no gaps
 * @param timestampMicros ingress time, microseconds since the Unix epoch (UTC)
 */
public record JournalEntry(long sequence, long timestampMicros, Command command) {

    public JournalEntry {
        Objects.requireNonNull(command, "command");
    }
}
