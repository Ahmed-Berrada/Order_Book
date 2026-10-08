package io.github.ahmedberrada.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property-based tests: random command streams, checked against the rulebook invariants and against
 * {@link ReferenceEngine}. Prices are drawn from a narrow band so that orders cross often, and a few
 * invalid values and unknown cancels are mixed in.
 */
class MatchingEnginePropertyTest {

    private static final Instrument INSTRUMENT = new Instrument("PROP", 50, 110);

    @Provide
    Arbitrary<List<Command>> commands() {
        Arbitrary<Side> side = Arbitraries.of(Side.class);
        Arbitrary<Long> price = Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.longs().between(95, 105)),
                Tuple.of(1, Arbitraries.of(-1L, 0L, 1L, 110L, 111L)));
        Arbitrary<Long> quantity = Arbitraries.frequencyOf(
                Tuple.of(30, Arbitraries.longs().between(1, 20)),
                Tuple.of(1, Arbitraries.of(-3L, 0L, 50L, 51L)));

        Arbitrary<Command> limit = Combinators.combine(side, price, quantity)
                .as((s, p, q) -> new LimitOrder(s, p, q));
        Arbitrary<Command> market = Combinators.combine(side, quantity)
                .as((s, q) -> new MarketOrder(s, q));
        Arbitrary<Command> cancel = Arbitraries.longs().between(0, 80)
                .map(id -> new CancelOrder(id));

        return Arbitraries.frequencyOf(Tuple.of(6, limit), Tuple.of(1, market), Tuple.of(2, cancel))
                .list().ofMaxSize(150);
    }

    @Property
    @Rulebook({"CT-001", "CT-002", "CT-003", "CT-004", "CT-005", "CT-006", "CT-007", "CT-008", "CT-009",
            "CT-010", "CT-011", "CT-012"})
    void producesExactlyTheSameEventsAsTheReferenceModel(@ForAll("commands") List<Command> commands) {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);
        ReferenceEngine reference = new ReferenceEngine(INSTRUMENT);

        for (Command command : commands) {
            assertThat(engine.process(command)).as("events for %s", command)
                    .isEqualTo(reference.process(command));
        }
    }

    @Property
    @Rulebook("CT-013")
    void bookIsNeverCrossedAndHoldsOnlyPositiveQuantities(@ForAll("commands") List<Command> commands) {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);

        for (Command command : commands) {
            engine.process(command);
            OrderBook book = engine.book();
            if (book.bestBid().isPresent() && book.bestAsk().isPresent()) {
                assertThat(book.bestBid().getAsLong()).isLessThan(book.bestAsk().getAsLong());
            }
            for (Side side : Side.values()) {
                assertThat(book.depth(side)).allSatisfy(level -> {
                    assertThat(level.quantity()).isPositive();
                    assertThat(level.orderCount()).isPositive();
                });
            }
        }
    }

    @Property
    @Rulebook("CT-014")
    void quantityIsConserved(@ForAll("commands") List<Command> commands) {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);
        Map<Long, Long> accepted = new HashMap<>();
        Map<Long, Long> filled = new HashMap<>();
        Map<Long, Long> cancelled = new HashMap<>();

        for (Command command : commands) {
            for (Event event : engine.process(command)) {
                switch (event) {
                    case OrderAccepted e -> accepted.put(e.orderId(), quantityOf(command));
                    case TradeExecuted e -> {
                        filled.merge(e.takerOrderId(), e.quantity(), Long::sum);
                        filled.merge(e.makerOrderId(), e.quantity(), Long::sum);
                    }
                    case OrderCancelled e -> cancelled.merge(e.orderId(), e.cancelledQuantity(), Long::sum);
                    case OrderRejected e -> { }
                    case OrderRested e -> { }
                    case CancelRejected e -> { }
                }
            }

            long restingTotal = 0;
            for (var order : accepted.entrySet()) {
                long id = order.getKey();
                long resting = engine.book().restingQuantity(id);
                restingTotal += resting;
                assertThat(filled.getOrDefault(id, 0L) + cancelled.getOrDefault(id, 0L) + resting)
                        .as("order %d", id).isEqualTo(order.getValue());
            }
            long depthTotal = 0;
            for (Side side : Side.values()) {
                depthTotal += engine.book().depth(side).stream().mapToLong(OrderBook.Level::quantity).sum();
            }
            assertThat(depthTotal).isEqualTo(restingTotal);
        }
    }

    @Property
    @Rulebook({"CT-010", "CT-011"})
    void orderIdsAndSequenceNumbersHaveNoGaps(@ForAll("commands") List<Command> commands) {
        MatchingEngine engine = new MatchingEngine(INSTRUMENT);
        long expectedSequence = 1;
        long expectedOrderId = 1;

        for (Command command : commands) {
            List<Event> events = engine.process(command);
            for (Event event : events) {
                assertThat(event.sequence()).isEqualTo(expectedSequence++);
            }
            if (!(command instanceof CancelOrder)) {
                long orderId = switch (events.getFirst()) {
                    case OrderAccepted e -> e.orderId();
                    case OrderRejected e -> e.orderId();
                    default -> throw new AssertionError("new order must start with accept or reject: " + events);
                };
                assertThat(orderId).isEqualTo(expectedOrderId++);
            }
        }
    }

    @Property
    @Rulebook("CT-015")
    void sameCommandsAlwaysProduceTheSameEvents(@ForAll("commands") List<Command> commands) {
        MatchingEngine first = new MatchingEngine(INSTRUMENT);
        MatchingEngine second = new MatchingEngine(INSTRUMENT);

        List<Event> firstRun = commands.stream().flatMap(c -> first.process(c).stream()).toList();
        List<Event> secondRun = commands.stream().flatMap(c -> second.process(c).stream()).toList();

        assertThat(secondRun).isEqualTo(firstRun);
    }

    private static long quantityOf(Command command) {
        return switch (command) {
            case LimitOrder order -> order.quantity();
            case MarketOrder order -> order.quantity();
            case CancelOrder cancel -> throw new AssertionError("a cancel is never accepted");
        };
    }
}
