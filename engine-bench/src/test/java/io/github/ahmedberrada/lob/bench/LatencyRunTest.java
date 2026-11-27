package io.github.ahmedberrada.lob.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

/** The harness runs and counts what it should; the numbers themselves are not asserted (they depend on the machine). */
class LatencyRunTest {

    @Test
    void theEngineRunRecordsEveryCommand() {
        LatencyRun.Result result = LatencyRun.runEngine(5_000, 1_000);

        assertThat(result.histogram().getTotalCount()).isEqualTo(5_000);
        assertThat(result.seconds()).isPositive();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        result.print(new PrintStream(out));
        assertThat(out.toString()).contains("engine").contains("p99.9");
    }

    @Test
    void theJournalRunsRecordEveryCommandAndCleanUp() throws IOException {
        assertThat(LatencyRun.runJournal(2_000, 500, FsyncPolicy.OS).histogram().getTotalCount()).isEqualTo(2_000);
        assertThat(LatencyRun.runJournal(200, 50, FsyncPolicy.EVERY_COMMAND).histogram().getTotalCount())
                .isEqualTo(200);
    }

    @Test
    void theWorkloadIsReproducibleAndMixesCommandKinds() {
        Workload first = new Workload(7, 100);
        Workload second = new Workload(7, 100);
        java.util.Set<Class<?>> kinds = new java.util.HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            var command = first.next();
            assertThat(command).isEqualTo(second.next());
            kinds.add(command.getClass());
        }
        assertThat(kinds).hasSize(3);                    // limit, cancel and market orders
    }

    @Test
    void aShortSoakRecoversToTheSameStateAfterEveryRestart() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        SoakRun.Result result = SoakRun.run(60_000_000_000L, 3_000, 10_000, new PrintStream(out));

        assertThat(result.consistent()).isTrue();
        assertThat(result.commands()).isEqualTo(10_000);
        assertThat(result.restarts()).isEqualTo(4);                  // 3 mid-run restarts and the final recovery
        assertThat(out.toString()).contains("state matches").doesNotContain("DIFFERS");
    }
}
