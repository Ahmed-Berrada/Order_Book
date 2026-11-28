package io.github.ahmedberrada.lob.journal;

import java.util.Objects;

/**
 * Tuning of a {@link JournaledEngine}.
 *
 * @param fsync            when the command journal is flushed to the device
 * @param snapshotInterval take a snapshot every this many commands; 0 disables automatic snapshots
 * @param sealing          how the command journal is sealed (RS-013), or {@code null} for no seals
 */
public record JournalOptions(FsyncPolicy fsync, int snapshotInterval, JournalSealing sealing) {

    /** No seals. */
    public JournalOptions(FsyncPolicy fsync, int snapshotInterval) {
        this(fsync, snapshotInterval, null);
    }

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
