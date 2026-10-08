package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Rulebook;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The real thing: a separate JVM journals commands and is killed with SIGKILL ({@code kill -9}) at an
 * arbitrary moment, possibly in the middle of a write or a snapshot. Recovery must keep every
 * acknowledged command and rebuild exactly the state those commands produce.
 *
 * <p>A process kill leaves the operating system's page cache intact, so this proves process-crash
 * safety; power-loss safety additionally relies on {@link FsyncPolicy#EVERY_COMMAND}.
 */
class ProcessCrashTest {

    private static final int COMMANDS = 50_000;

    @TempDir
    Path directory;

    @ParameterizedTest(name = "seed {0}, killed after {1} acknowledgements")
    @CsvSource({"1, 37", "2, 500", "3, 2000", "4, 6000", "5, 15000"})
    @Rulebook({"RS-001", "RS-002", "RS-003", "RS-007"})
    void killedProcessLosesNoAcknowledgedCommand(long seed, int killAfter) throws Exception {
        long lastAcknowledged = runAndKill(seed, killAfter);

        List<Command> commands = CommandStream.generate(seed, COMMANDS);
        try (JournaledEngine engine = JournaledEngine.open(directory, CommandStream.INSTRUMENT,
                new JournalOptions(FsyncPolicy.OS, 0), TimeSource.system())) {
            long recovered = engine.recovery().lastCommandSequence();
            assertThat(recovered).isGreaterThanOrEqualTo(lastAcknowledged);
            JournaledEngineTest.assertSameState(engine, CommandStream.expectedAfter(commands, recovered));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = ReplayTool.run(new String[] {directory.toString(), "--verify"},
                new PrintStream(out, true, StandardCharsets.UTF_8), System.err);
        assertThat(exit).as(out.toString(StandardCharsets.UTF_8)).isZero();
    }

    /** Starts the writer, kills it once it has acknowledged {@code killAfter} commands. */
    private long runAndKill(long seed, int killAfter) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                CrashingWriter.class.getName(), directory.toString(), Long.toString(seed),
                Integer.toString(COMMANDS), "1000")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        long lastAcknowledged = 0;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (lastAcknowledged < killAfter && (line = out.readLine()) != null) {
                lastAcknowledged = Long.parseLong(line.trim());
            }
            child.destroyForcibly();          // SIGKILL: no shutdown hooks, no close()
            assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(lastAcknowledged).as("child acknowledged commands before being killed").isEqualTo(killAfter);
        return lastAcknowledged;
    }
}
