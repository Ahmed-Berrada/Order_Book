package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Reproducible stream of realistic commands: the same seed always gives the same commands. */
final class CommandStream {

    static final Instrument INSTRUMENT = new Instrument("JRNL", 100, 1_000);

    private CommandStream() {
    }

    static List<Command> generate(long seed, int count) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Command> commands = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            int kind = random.nextInt(10);
            if (kind < 6) {
                commands.add(new LimitOrder(side, random.nextLong(95, 106), random.nextLong(1, 30)));
            } else if (kind < 7) {
                commands.add(new MarketOrder(side, random.nextLong(1, 30)));
            } else {
                commands.add(new CancelOrder(random.nextLong(1, i + 2)));
            }
        }
        return commands;
    }

    /** A fresh engine fed the first {@code count} commands: the state recovery must reproduce. */
    static MatchingEngine expectedAfter(List<Command> commands, long count) {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);
        commands.subList(0, (int) count).forEach(engine::process);
        return engine;
    }
}
