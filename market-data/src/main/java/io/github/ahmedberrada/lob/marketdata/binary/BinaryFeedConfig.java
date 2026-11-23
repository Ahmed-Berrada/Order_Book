package io.github.ahmedberrada.lob.marketdata.binary;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

/**
 * Configuration of the binary feed (docs/interfaces/market-data-binary.md).
 *
 * @param streamTarget   where packets are sent: a multicast group or a unicast address
 * @param serviceAddress where the TCP retransmission and snapshot service listens (port 0: any free port)
 * @param session        the stream's session name, at most 10 ASCII characters
 * @param retention      messages kept for retransmission (BF-004)
 * @param heartbeat      idle time after which a heartbeat is sent (BF-002)
 * @param queueCapacity  updates buffered between the instruments and the sender
 */
public record BinaryFeedConfig(InetSocketAddress streamTarget, InetSocketAddress serviceAddress, String session,
        int retention, Duration heartbeat, int queueCapacity) {

    public BinaryFeedConfig {
        Objects.requireNonNull(streamTarget, "streamTarget");
        Objects.requireNonNull(serviceAddress, "serviceAddress");
        Objects.requireNonNull(heartbeat, "heartbeat");
        if (session == null || session.isEmpty() || session.length() > BinaryCodec.SESSION_BYTES) {
            throw new IllegalArgumentException("session must be 1 to 10 characters: " + session);
        }
        if (retention < 1 || queueCapacity < 1 || heartbeat.isNegative() || heartbeat.isZero()) {
            throw new IllegalArgumentException("retention, queue capacity and heartbeat must be positive");
        }
    }
}
