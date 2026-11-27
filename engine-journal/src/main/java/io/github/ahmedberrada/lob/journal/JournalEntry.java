package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Command;
import java.util.Objects;

/**
 * One record of the command journal.
 *
 * @param sequence        position in the journal: 1, 2, 3… with no gaps
 * @param timestampMicros ingress time, microseconds since the Unix epoch (UTC)
 * @param memberId        the member the command was processed for; 0 for the operator (MB-001)
 */
public record JournalEntry(long sequence, long timestampMicros, Command command, long memberId) {

    public JournalEntry {
        Objects.requireNonNull(command, "command");
        if (memberId < 0) {
            throw new IllegalArgumentException("member ID must not be negative: " + memberId);
        }
    }

    /** A command of the operator, member 0. */
    public JournalEntry(long sequence, long timestampMicros, Command command) {
        this(sequence, timestampMicros, command, 0);
    }
}
