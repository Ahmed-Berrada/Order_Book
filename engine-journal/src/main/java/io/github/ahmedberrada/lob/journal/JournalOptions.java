package io.github.ahmedberrada.lob.journal;

import java.util.Objects;

/**
 * Tuning of a {@link JournaledEngine}.
 *
 * @param fsync            when the command journal is flushed to the device
 * @param snapshotInterval take a snapshot every this many commands; 0 disables automatic snapshots
 */
public record JournalOptions(FsyncPolicy fsync, int snapshotInterval) {

    public JournalOptions {
        Objects.requireNonNull(fsync, "fsync");
        if (snapshotInterval < 0) {
            throw new IllegalArgumentException("snapshotInterval must not be negative: " + snapshotInterval);
        }
    }

    /** Safe defaults: flush every command, snapshot every 10,000 commands. */
    public static JournalOptions defaults() {
        return new JournalOptions(FsyncPolicy.EVERY_COMMAND, 10_000);
    }
}
