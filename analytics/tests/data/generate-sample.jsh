// Regenerates sample-trades.csv and sample-records.csv (see README in this directory): 300 commands from
// five members, with two call phases, written by the real engine and exported by the real tools.
import io.github.ahmedberrada.lob.core.*;
import io.github.ahmedberrada.lob.journal.*;
var dir = java.nio.file.Path.of("target/sample/AAPL");
var r = new java.util.SplittableRandom(7);
long[] t = {1_795_683_600_000_000L};
try (var e = JournaledEngine.open(dir, new Instrument("AAPL", 1000, 100000), new JournalOptions(FsyncPolicy.OS, 0), () -> t[0] += 2_000_000)) {
  for (int i = 0; i < 300; i++) {
    Side s = r.nextBoolean() ? Side.BUY : Side.SELL;
    Command c = i % 150 == 99 ? new SetPhase(TradingPhase.CALL) : i % 150 == 120 ? new SetPhase(TradingPhase.CONTINUOUS)
      : r.nextInt(5) == 0 ? new MarketOrder(s, r.nextLong(1, 30)) : new LimitOrder(s, 18500 + r.nextLong(-20, 21), r.nextLong(1, 50));
    e.process(c, r.nextLong(1, 6));
  }
}
/exit
