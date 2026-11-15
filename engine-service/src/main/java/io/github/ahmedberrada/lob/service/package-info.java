/**
 * Access to the matching engines of all listed instruments: routing, one writer thread and bounded
 * queue per instrument, price conversion and refusals (ADR-0004, rulebook chapter
 * {@code order-entry.md}).
 *
 * <p>Plain Java, like the core and the journal. Protocol adapters (REST, FIX) build on
 * {@link io.github.ahmedberrada.lob.service.MatchingService}.
 */
package io.github.ahmedberrada.lob.service;
