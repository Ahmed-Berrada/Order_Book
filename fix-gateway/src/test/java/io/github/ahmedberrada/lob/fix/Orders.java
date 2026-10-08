package io.github.ahmedberrada.lob.fix;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.field.ClOrdID;
import quickfix.field.OrdType;
import quickfix.field.OrderQty;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.TransactTime;
import quickfix.fix44.NewOrderSingle;

/** Builds member messages for tests; decimals are set by tag, never as doubles. */
final class Orders {

    private Orders() {
    }

    static Message limit(String clOrdId, char side, String price, String quantity) {
        Message order = order(clOrdId, "AAPL", side, OrdType.LIMIT, quantity);
        order.setDecimal(Price.FIELD, new BigDecimal(price));
        return order;
    }

    static Message market(String clOrdId, char side, String quantity) {
        return order(clOrdId, "AAPL", side, OrdType.MARKET, quantity);
    }

    static Message order(String clOrdId, String symbol, char side, char type, String quantity) {
        NewOrderSingle order = new NewOrderSingle();
        order.setString(ClOrdID.FIELD, clOrdId);
        order.setString(Symbol.FIELD, symbol);
        order.setChar(Side.FIELD, side);
        order.setChar(OrdType.FIELD, type);
        order.setUtcTimeStamp(TransactTime.FIELD, LocalDateTime.now(ZoneOffset.UTC));
        if (quantity != null) {
            order.setDecimal(OrderQty.FIELD, new BigDecimal(quantity));
        }
        return order;
    }

    /** A field as text, for exact comparisons of decimals and identifiers. */
    static String field(Message message, int tag) throws FieldNotFound {
        return message.getString(tag);
    }
}
