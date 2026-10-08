/**
 * FIX 4.4 order-entry gateway in front of the
 * {@link io.github.ahmedberrada.lob.service.MatchingService} (ADR-0006,
 * {@code docs/interfaces/fix-rules-of-engagement.md}). QuickFIX/J provides the session layer; this
 * package translates application messages to commands and events to execution reports.
 */
package io.github.ahmedberrada.lob.fix;
