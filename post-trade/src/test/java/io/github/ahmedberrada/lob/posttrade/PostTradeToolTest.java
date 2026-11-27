package io.github.ahmedberrada.lob.posttrade;

import static io.github.ahmedberrada.lob.posttrade.Journals.by;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.Rulebook;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.TradingPhase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PostTradeToolTest {

    private static final String FEES = "maker=-0.2,taker=0.3,auction=0.1";

    @TempDir
    Path directory;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void writeAMorning() throws IOException {
        Journals.write(directory, Journals.MORNING, List.of(
                by(1, new LimitOrder(Side.SELL, 18_550, 400)),                    // order 1
                by(2, new LimitOrder(Side.BUY, 18_560, 100)),                     // order 2 takes 100 at 185.50
                by(0, new SetPhase(TradingPhase.CALL)),
                by(3, new LimitOrder(Side.BUY, 18_600, 50)),                      // order 3: uncross at 185.50
                by(0, new SetPhase(TradingPhase.CONTINUOUS))));
    }

    private int run(String... args) {
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        return PostTradeTool.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private List<String> lines() {
        return out.toString(StandardCharsets.UTF_8).lines().toList();
    }

    @Test
    @Rulebook({"PT-001", "PT-002", "PT-003", "PT-004", "PT-005", "PT-007"})
    void theTradeFileHasOneLinePerSideOfEveryTrade() {
        assertThat(run("trades", directory.toString(), "--tick-size", "0.01", "--fees", FEES)).isZero();
        assertThat(lines()).containsExactly(PostTradeTool.TRADES_HEADER,
                "PTX-4,2026-11-26,2026-11-26T09:00:00.000001Z,PTX,2,2,BUY,TAKER,18550,185.50,100,18550.00,0.3,0.56",
                "PTX-4,2026-11-26,2026-11-26T09:00:00.000001Z,PTX,1,1,SELL,MAKER,18550,185.50,100,18550.00,-0.2,-0.37",
                "PTX-9,2026-11-26,2026-11-26T09:00:00.000004Z,PTX,3,3,BUY,AUCTION,18550,185.50,50,9275.00,0.1,0.09",
                "PTX-9,2026-11-26,2026-11-26T09:00:00.000004Z,PTX,1,1,SELL,AUCTION,18550,185.50,50,9275.00,0.1,0.09");
        assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    @Rulebook({"PT-008", "PT-009"})
    void theStatementSumsTheSameRecords() {
        assertThat(run("statement", directory.toString(), "--fees", FEES, "--tick-size", "0.01",
                "--currency-scale", "3")).isZero();
        assertThat(lines()).containsExactly(PostTradeTool.STATEMENT_HEADER,
                "2026-11-26,PTX,1,2,0,0,150,27825.00,-0.278",                     // -0.371 + 0.093
                "2026-11-26,PTX,2,1,100,18550.00,0,0,0.556",                     // 0.5565, half to even
                "2026-11-26,PTX,3,1,50,9275.00,0,0,0.093");
    }

    @Test
    @Rulebook("PT-010")
    void producingTheFilesWritesNothingAndIsRepeatable() throws IOException {
        List<byte[]> before = contents();
        run("trades", directory.toString(), "--tick-size", "0.01", "--fees", FEES);
        String first = out.toString(StandardCharsets.UTF_8);
        run("trades", directory.toString(), "--tick-size", "0.01", "--fees", FEES);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(first);
        run("statement", directory.toString(), "--tick-size", "0.01", "--fees", FEES);
        assertThat(contents()).usingRecursiveFieldByFieldElementComparator().containsExactlyElementsOf(before);
    }

    private List<byte[]> contents() throws IOException {
        List<byte[]> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(directory)) {
            for (Path file : list.sorted().toList()) {
                files.add(Files.readAllBytes(file));
            }
        }
        return files;
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "trades", "invoices DIR --tick-size 0.01 --fees F", "trades DIR DIR --tick-size 0.01 --fees F",
        "trades DIR --fees F", "trades DIR --tick-size 0.01", "trades DIR --tick-size 0.01 --fees F --colour red",
        "trades DIR --tick-size 0.01 --fees", "trades DIR --tick-size cheap --fees F",
        "trades DIR --tick-size 0 --fees F", "trades DIR --tick-size 0.01 --fees F --currency-scale two",
        "trades DIR --tick-size 0.01 --fees maker=1", "trades DIR --tick-size 0.01 --fees F --currency-scale 9"})
    void aWrongCommandLineIsAUsageError(String line) {
        String[] args = line.isEmpty() ? new String[0]
                : line.replace("DIR", directory.toString()).replace(" F", " " + FEES).split(" ");
        assertThat(run(args)).isEqualTo(PostTradeTool.USAGE);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).startsWith("error: ").contains("usage: lob-post-trade");
    }

    @Test
    void aMissingOrIncompleteJournalProducesNothing(@TempDir Path empty, @TempDir Path behind) throws IOException {
        assertThat(run("trades", empty.toString(), "--tick-size", "0.01", "--fees", FEES)).isEqualTo(PostTradeTool.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("no command journal");

        Journals.write(behind, Journals.MORNING, List.of(by(1, new LimitOrder(Side.SELL, 100, 1))));
        Path oneCommand = behind.resolve("events.journal");
        Path saved = empty.resolve("events.saved");
        Files.copy(oneCommand, saved);
        Journals.write(behind, Journals.MORNING + 10, List.of(by(2, new LimitOrder(Side.BUY, 100, 1))));
        Files.copy(saved, oneCommand, StandardCopyOption.REPLACE_EXISTING);   // a crash lost the trade's batch

        assertThat(run("statement", behind.toString(), "--tick-size", "0.01", "--fees", FEES))
                .isEqualTo(PostTradeTool.FAILED);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("ends at command 1 of 2");
    }

    @Test
    @Rulebook("PT-008")
    void recordsThatDoNotBalanceProduceNothing() {
        FeeSchedule fees = FeeSchedule.parse(FEES, 2);
        TradeRecord buy = StatementTest.record(StatementTest.MONDAY, "PTX", 1, Side.BUY, 5, "50.00", "0.01");
        TradeRecord sell = StatementTest.record(StatementTest.MONDAY, "PTX", 2, Side.SELL, 4, "40.00", "0.01");
        for (boolean statement : new boolean[] {false, true}) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            assertThat(PostTradeTool.write(List.of(buy, sell), statement, fees,
                    new PrintStream(stdout, true, StandardCharsets.UTF_8),
                    new PrintStream(stderr, true, StandardCharsets.UTF_8))).isEqualTo(PostTradeTool.FAILED);
            assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
            assertThat(stderr.toString(StandardCharsets.UTF_8))
                    .isEqualTo("error: the trades do not balance on 2026-11-23: 5 lots bought but 4 sold (PT-008)"
                            + System.lineSeparator());
        }
    }

    @Test
    @Rulebook("PT-010")
    void theFormatDoesNotDependOnHowTheTimeOrTheRatesFall(@TempDir Path onTheSecond) throws IOException {
        Journals.write(onTheSecond, Journals.MORNING - 1, List.of(                // the trade at exactly 09:00:00
                by(1, new LimitOrder(Side.SELL, 100, 1)),
                by(2, new LimitOrder(Side.BUY, 100, 1))));
        assertThat(run("trades", onTheSecond.toString(), "--tick-size", "0.01",
                "--fees", "maker=-0.20,taker=0.30,auction=0.10")).isZero();
        assertThat(lines()).element(1).isEqualTo(
                "PTX-4,2026-11-26,2026-11-26T09:00:00.000000Z,PTX,2,2,BUY,TAKER,100,1.00,1,1.00,0.3,0.00");
    }
}
