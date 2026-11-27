package io.github.ahmedberrada.lob.posttrade;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.TickBand;
import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.journal.JournaledEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Writes journals for the tests: commands with their members, one microsecond apart from a given time. */
final class Journals {

    static final Instrument INSTRUMENT = new Instrument("PTX", 1_000, 100_000, List.of(new TickBand(1, 1)), 0);

    /** 2026-11-26T23:59:59.999998Z: the third command is on the next trade date. */
    static final long BEFORE_MIDNIGHT = 1_795_737_599_999_998L;

    /** 2026-11-26T09:00:00Z. */
    static final long MORNING = 1_795_683_600_000_000L;

    record Entry(Command command, long memberId) {
    }

    private Journals() {
    }

    static Entry by(long memberId, Command command) {
        return new Entry(command, memberId);
    }

    static void write(Path directory, long startMicros, List<Entry> entries) throws IOException {
        long[] now = {startMicros};
        try (JournaledEngine engine = JournaledEngine.open(directory, INSTRUMENT, new JournalOptions(FsyncPolicy.OS, 0),
                () -> now[0]++)) {
            for (Entry entry : entries) {
                engine.process(entry.command(), entry.memberId());
            }
        }
    }
}
