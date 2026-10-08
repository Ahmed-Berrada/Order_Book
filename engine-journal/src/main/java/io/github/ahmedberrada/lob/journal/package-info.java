/**
 * Durability for the matching engine: write-ahead command journal, event journal, snapshots and
 * recovery (ADR-0003, rulebook chapter {@code resilience.md}).
 *
 * <p>Rules for this package:
 * <ul>
 *   <li>Plain Java: no Spring, no logging framework. Outcomes are returned, not logged.</li>
 *   <li>Fail-stop: after an I/O error the engine refuses further commands rather than guess its state.</li>
 *   <li>Only {@link io.github.ahmedberrada.lob.journal.TimeSource} reads the system clock.</li>
 * </ul>
 */
package io.github.ahmedberrada.lob.journal;
