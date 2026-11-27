package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.core.SelfTradeMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration of the FIX acceptor (ADR-0006 §2).
 *
 * @param bindAddress     interface to listen on; {@code 127.0.0.1} by default, since sessions are not
 *                        authenticated yet (phase 8)
 * @param port            TCP port to accept member connections on
 * @param senderCompId    the venue's CompID, {@code LOB} by default
 * @param memberCompIds   one TargetCompID per member session, provisioned in advance
 * @param dropCopyCompIds read-only sessions that receive a copy of every execution report (DC-001)
 * @param storeDirectory  where sequence numbers and sent messages are persisted
 * @param selfTradeMode   what the venue does when a member's order meets its own resting order (SP-002 to
 *                        SP-004); every member session is its own self-trade prevention group
 */
public record FixGatewayConfig(
        String bindAddress, int port, String senderCompId, List<String> memberCompIds, List<String> dropCopyCompIds,
        Path storeDirectory, SelfTradeMode selfTradeMode) {

    /** Cancels the incoming order when it meets one of its own member's orders. */
    public FixGatewayConfig(
            String bindAddress, int port, String senderCompId, List<String> memberCompIds,
            List<String> dropCopyCompIds, Path storeDirectory) {
        this(bindAddress, port, senderCompId, memberCompIds, dropCopyCompIds, storeDirectory,
                SelfTradeMode.CANCEL_NEWEST);
    }

    /** Listens on the loopback interface only, with no drop copy session. */
    public FixGatewayConfig(int port, String senderCompId, List<String> memberCompIds, Path storeDirectory) {
        this("127.0.0.1", port, senderCompId, memberCompIds, List.of(), storeDirectory);
    }

    /**
     * The self-trade prevention group of a member (SP-001): a number derived from its CompID alone, so
     * that reordering or extending the configured members never gives a journaled order another owner.
     * {@link String#hashCode()} is specified by the language, hence stable across JVMs and releases.
     */
    public static long selfTradeGroup(String compId) {
        return compId.hashCode() & 0x7fff_ffffL;
    }

    public FixGatewayConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(senderCompId, "senderCompId");
        Objects.requireNonNull(storeDirectory, "storeDirectory");
        Objects.requireNonNull(selfTradeMode, "selfTradeMode");
        memberCompIds = List.copyOf(memberCompIds);
        dropCopyCompIds = List.copyOf(dropCopyCompIds);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (memberCompIds.isEmpty()) {
            throw new IllegalArgumentException("at least one member session is required");
        }
        List<String> all = new ArrayList<>(memberCompIds);
        all.addAll(dropCopyCompIds);
        if (all.stream().distinct().count() != all.size()) {
            throw new IllegalArgumentException("duplicate CompID among members and drop copies: " + all);
        }
        // two members in one group would be prevented from trading with each other: refuse to start instead
        Set<Long> groups = new HashSet<>();
        for (String member : memberCompIds) {
            if (selfTradeGroup(member) == 0 || !groups.add(selfTradeGroup(member))) {
                throw new IllegalArgumentException("self-trade group collision for member " + member);
            }
        }
    }
}
