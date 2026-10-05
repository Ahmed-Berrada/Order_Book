/**
 * Matching engine core.
 *
 * <p>Rules for this package (see {@code docs/adr/0001-foundations.md}):
 * <ul>
 *   <li>Pure Java: no Spring, no I/O, no logging framework.</li>
 *   <li>Not thread-safe by contract: an engine instance is owned by exactly one thread.</li>
 *   <li>Deterministic: no system clock, no randomness. Same commands in, same events out.</li>
 *   <li>Prices and quantities are {@code long} ticks/lots, never {@code double}.</li>
 * </ul>
 */
package io.github.ahmedberrada.lob.core;
