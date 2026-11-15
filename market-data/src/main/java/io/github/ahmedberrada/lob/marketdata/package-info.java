/**
 * Level-2 market data built from the matching service's event stream (ADR-0007, rulebook chapter
 * {@code market-data.md}): one {@link io.github.ahmedberrada.lob.marketdata.BookImage} per instrument,
 * snapshots and incremental updates. Plain Java; transports live elsewhere.
 */
package io.github.ahmedberrada.lob.marketdata;
