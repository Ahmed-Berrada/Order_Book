package io.github.ahmedberrada.lob.posttrade;

/** How an order took part in a trade, which decides its fee rate (PT-003). */
public enum Liquidity {
    /** The order that arrived and traded against the book. */
    TAKER,
    /** The resting order it traded with. */
    MAKER,
    /** Either order of a trade of an uncross: there is no aggressor in an auction. */
    AUCTION
}
