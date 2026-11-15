package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.Command;
import java.nio.file.Path;
import java.util.List;

/**
 * Child process of {@link ProcessCrashTest}: processes a reproducible command stream and prints the
 * sequence number of each command once it is acknowledged, until it is killed.
 *
 * <p>Arguments: directory, seed, command count, snapshot interval.
 */
public final class CrashingWriter {

    private CrashingWriter() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        List<Command> commands = CommandStream.generate(Long.parseLong(args[1]), Integer.parseInt(args[2]));
        JournalOptions options = new JournalOptions(FsyncPolicy.OS, Integer.parseInt(args[3]));
        try (JournaledEngine engine = JournaledEngine.open(directory, CommandStream.INSTRUMENT, options, TimeSource.system())) {
            for (long i = engine.lastCommandSequence(); i < commands.size(); i++) {
                EventBatch batch = engine.process(commands.get((int) i));
                System.out.println(batch.commandSequence());
                System.out.flush();
            }
        }
        Thread.sleep(60_000);   // finished: wait to be killed like the others
    }
}
