package io.github.ahmedberrada.lob.api;

/** Order types accepted by the REST API. */
public enum OrderType {
    LIMIT,
    MARKET,
    /** Becomes a MARKET order when a trade reaches its stop price (ST-001). */
    STOP,
    /** Becomes a LIMIT order when a trade reaches its stop price (ST-001). */
    STOP_LIMIT;

    boolean hasLimitPrice() {
        return this == LIMIT || this == STOP_LIMIT;
    }

    boolean isStop() {
        return this == STOP || this == STOP_LIMIT;
    }
}
