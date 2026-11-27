package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.CancelOrder;
import io.github.ahmedberrada.lob.core.Command;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.LimitOrder;
import io.github.ahmedberrada.lob.core.MarketOrder;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import io.github.ahmedberrada.lob.core.ReplaceOrder;
import io.github.ahmedberrada.lob.core.SelfTradeInstruction;
import io.github.ahmedberrada.lob.core.SelfTradeMode;
import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.core.SetPhase;
import io.github.ahmedberrada.lob.core.StopOrder;
import io.github.ahmedberrada.lob.core.TickBand;
import io.github.ahmedberrada.lob.core.TimeInForce;
import io.github.ahmedberrada.lob.core.TradingPhase;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/** Reproducible stream of realistic commands: the same seed always gives the same commands. */
final class CommandStream {

    static final Instrument INSTRUMENT = new Instrument("JRNL", 100, 1_000,
            List.of(new TickBand(1, 1), new TickBand(100, 1)), 300);

    private CommandStream() {
    }

    static List<Command> generate(long seed, int count) {
        SplittableRandom random = new SplittableRandom(seed);
        List<Command> commands = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            int kind = random.nextInt(15);
            TimeInForce[] tifs = TimeInForce.values();
            SelfTradeInstruction selfTrade = random.nextInt(3) == 0
                    ? new SelfTradeInstruction(random.nextLong(1, 3), SelfTradeMode.values()[random.nextInt(3)])
                    : SelfTradeInstruction.NONE;
            if (kind < 5) {
                commands.add(new LimitOrder(side, random.nextLong(95, 106), random.nextLong(1, 30), TimeInForce.GTC,
                        false, 0, selfTrade));
            } else if (kind < 6) {
                commands.add(new LimitOrder(side, random.nextLong(95, 106), random.nextLong(1, 30),
                        tifs[random.nextInt(tifs.length)], random.nextInt(3) == 0, 0));
            } else if (kind < 7 && random.nextBoolean()) {
                commands.add(new LimitOrder(side, random.nextLong(95, 106), random.nextLong(10, 30),
                        TimeInForce.GTC, false, random.nextLong(1, 10)));
            } else if (kind < 7) {
                commands.add(new MarketOrder(side, random.nextLong(1, 30), random.nextBoolean() ? TimeInForce.IOC
                        : TimeInForce.FOK, selfTrade));
            } else if (kind < 9) {
                commands.add(new ReplaceOrder(random.nextLong(1, i + 2), random.nextLong(95, 106),
                        random.nextLong(1, 30)));
            } else if (kind == 12) {
                commands.add(new StopOrder(side, random.nextLong(95, 106),
                        random.nextBoolean() ? 0 : random.nextLong(95, 106), random.nextLong(1, 30), selfTrade));
            } else if (kind == 13) {
                commands.add(new SetPhase(TradingPhase.values()[random.nextInt(3)]));
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
