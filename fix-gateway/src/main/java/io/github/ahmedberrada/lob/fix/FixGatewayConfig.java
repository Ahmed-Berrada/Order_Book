package io.github.ahmedberrada.lob.fix;

import java.nio.file.Path;
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
 * @param storeDirectory  where sequence numbers and sent messages are persisted
 */
public record FixGatewayConfig(
        String bindAddress, int port, String senderCompId, List<String> memberCompIds, Path storeDirectory) {

    /** Listens on the loopback interface only. */
    public FixGatewayConfig(int port, String senderCompId, List<String> memberCompIds, Path storeDirectory) {
        this("127.0.0.1", port, senderCompId, memberCompIds, storeDirectory);
    }

    public FixGatewayConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(senderCompId, "senderCompId");
        Objects.requireNonNull(storeDirectory, "storeDirectory");
        memberCompIds = List.copyOf(memberCompIds);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (memberCompIds.isEmpty()) {
            throw new IllegalArgumentException("at least one member session is required");
        }
        if (memberCompIds.stream().distinct().count() != memberCompIds.size()) {
            throw new IllegalArgumentException("duplicate member CompID: " + memberCompIds);
        }
    }
}
