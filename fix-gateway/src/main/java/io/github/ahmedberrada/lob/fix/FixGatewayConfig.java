package io.github.ahmedberrada.lob.fix;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 */
public record FixGatewayConfig(
        String bindAddress, int port, String senderCompId, List<String> memberCompIds, List<String> dropCopyCompIds,
        Path storeDirectory) {

    /** Listens on the loopback interface only, with no drop copy session. */
    public FixGatewayConfig(int port, String senderCompId, List<String> memberCompIds, Path storeDirectory) {
        this("127.0.0.1", port, senderCompId, memberCompIds, List.of(), storeDirectory);
    }

    public FixGatewayConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(senderCompId, "senderCompId");
        Objects.requireNonNull(storeDirectory, "storeDirectory");
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
    }
}
