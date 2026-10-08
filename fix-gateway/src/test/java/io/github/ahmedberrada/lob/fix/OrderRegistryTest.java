package io.github.ahmedberrada.lob.fix;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmedberrada.lob.fix.OrderRegistry.FixOrder;
import org.junit.jupiter.api.Test;
import quickfix.SessionID;
import quickfix.field.OrdStatus;
import quickfix.field.Side;

class OrderRegistryTest {

    private static final SessionID MEMBER1 = new SessionID(FixGateway.BEGIN_STRING, "LOB", "MEMBER1");
    private static final SessionID MEMBER2 = new SessionID(FixGateway.BEGIN_STRING, "LOB", "MEMBER2");

    private final OrderRegistry registry = new OrderRegistry();

    @Test
    void clOrdIdIsPendingThenRefusedOrBoundToAnOrder() {
        assertThat(registry.slot(MEMBER1, "A")).isNull();
        assertThat(registry.reserve(MEMBER1, "A")).isTrue();
        assertThat(registry.reserve(MEMBER1, "A")).isFalse();
        assertThat(registry.reserve(MEMBER2, "A")).isTrue();
        assertThat(registry.slot(MEMBER1, "A").isPending()).isTrue();
        assertThat(registry.slot(MEMBER1, "B")).isNull();

        registry.refused(MEMBER1, "A");
        assertThat(registry.slot(MEMBER1, "A").isPending()).isFalse();
        assertThat(registry.slot(MEMBER1, "A").order()).isNull();

        FixOrder order = new FixOrder(MEMBER2, "A", FixVenue.AAPL, Side.BUY, 7, 10, OrdStatus.NEW);
        registry.created(order);
        assertThat(registry.slot(MEMBER2, "A").order()).isSameAs(order);
        assertThat(registry.slot(MEMBER2, "A").isPending()).isFalse();
        assertThat(registry.byOrderId("AAPL", 7)).isSameAs(order);
        assertThat(registry.byOrderId("AAPL", 8)).isNull();
        assertThat(order.fixOrderId()).isEqualTo("AAPL-7");
    }

    @Test
    void tracksCumulativeQuantityAndAveragePrice() {
        FixOrder order = new FixOrder(MEMBER1, "A", FixVenue.AAPL, Side.BUY, 1, 10, OrdStatus.NEW);
        assertThat(order.averagePrice()).isZero();

        order.fill(18_530, 3, 7);
        order.fill(18_540, 4, 3);

        assertThat(order.cumulativeQuantity()).isEqualTo(7);
        assertThat(order.leavesQuantity()).isEqualTo(3);
        assertThat(order.ordStatus()).isEqualTo(OrdStatus.PARTIALLY_FILLED);
        assertThat(order.averagePrice().toPlainString()).isEqualTo("185.3571428571");
        order.cancel();
        assertThat(order.ordStatus()).isEqualTo(OrdStatus.CANCELED);
        assertThat(order.leavesQuantity()).isZero();
        assertThat(new FixOrder(MEMBER1, "R", FixVenue.AAPL, Side.BUY, 2, 10, OrdStatus.REJECTED).leavesQuantity()).isZero();
    }
}
