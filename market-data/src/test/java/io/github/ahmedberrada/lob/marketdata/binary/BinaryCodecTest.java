package io.github.ahmedberrada.lob.marketdata.binary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.ahmedberrada.lob.core.Side;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryCodec.Packet;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Directory;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Level;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.SnapshotEnd;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.Trade;
import io.github.ahmedberrada.lob.marketdata.binary.BinaryMessage.UpdateEnd;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class BinaryCodecTest {

    @Property
    void everyMessageSurvivesAPacketRoundTrip(@ForAll @IntRange(max = 65_535) int locate, @ForAll long price,
            @ForAll long quantity, @ForAll int orders, @ForAll long sequence, @ForAll boolean buy) {
        Side side = buy ? Side.BUY : Side.SELL;
        List<BinaryMessage> messages = List.of(
                new Directory(locate, "AAPL", 1, (byte) -2),
                new Level(locate, side, price, quantity, orders),
                new Trade(locate, side, price, quantity),
                new UpdateEnd(locate, sequence, sequence, quantity),
                new SnapshotEnd(locate, sequence, price));
        byte[] packet = BinaryCodec.packet("LOB-1", sequence, messages.size(),
                messages.stream().map(BinaryCodec::encode).toList());

        Packet decoded = BinaryCodec.decodePacket(ByteBuffer.wrap(packet));

        assertThat(decoded).isEqualTo(new Packet("LOB-1", sequence, 5, messages));
        assertThat(decoded.isHeartbeat()).isFalse();
        assertThat(decoded.isRefused()).isFalse();
    }

    @Test
    void layoutsMatchTheSpecification() {
        assertThat(BinaryCodec.encode(new Directory(1, "AAPL", 1, (byte) -2))).hasSize(1 + 2 + 8 + 8 + 1);
        assertThat(BinaryCodec.encode(new Level(1, Side.BUY, 1, 1, 1))).hasSize(1 + 2 + 1 + 8 + 8 + 4)
                .startsWith((byte) 'L', (byte) 0, (byte) 1, (byte) 'B');
        assertThat(BinaryCodec.encode(new Trade(1, Side.SELL, 1, 1))).hasSize(1 + 2 + 1 + 8 + 8);
        assertThat(BinaryCodec.encode(new UpdateEnd(1, 1, 1, 1))).hasSize(1 + 2 + 8 + 8 + 8);
        assertThat(BinaryCodec.encode(new SnapshotEnd(1, 1, 1))).hasSize(1 + 2 + 8 + 8);
        byte[] heartbeat = BinaryCodec.packet("LOB-1", 42, 0, List.of());
        assertThat(heartbeat).hasSize(BinaryCodec.HEADER_BYTES);
        assertThat(BinaryCodec.decodePacket(ByteBuffer.wrap(heartbeat)).isHeartbeat()).isTrue();
        byte[] refused = BinaryCodec.packet("LOB-1", 1, BinaryCodec.REFUSED, List.of());
        assertThat(BinaryCodec.decodePacket(ByteBuffer.wrap(refused)).isRefused()).isTrue();
    }

    @Test
    void malformedInputIsRejected() {
        byte[] good = BinaryCodec.packet("LOB-1", 1, 1, List.of(BinaryCodec.encode(new Trade(1, Side.BUY, 1, 1))));
        assertThatIllegalArgumentException().isThrownBy(() -> BinaryCodec.decodePacket(ByteBuffer.wrap(Arrays.copyOf(good, 25))));
        assertThatIllegalArgumentException().isThrownBy(() -> BinaryCodec.decodePacket(ByteBuffer.wrap(Arrays.copyOf(good, good.length + 1))));
        byte[] badType = good.clone();
        badType[BinaryCodec.HEADER_BYTES + 2] = 'Q';
        assertThatIllegalArgumentException().isThrownBy(() -> BinaryCodec.decodePacket(ByteBuffer.wrap(badType)));
        byte[] badSide = good.clone();
        badSide[BinaryCodec.HEADER_BYTES + 2 + 3] = 'X';
        assertThatIllegalArgumentException().isThrownBy(() -> BinaryCodec.decodePacket(ByteBuffer.wrap(badSide)));
        byte[] tooLong = BinaryCodec.packet("LOB-1", 1, 1, List.of(Arrays.copyOf(BinaryCodec.encode(new Trade(1, Side.BUY, 1, 1)), 30)));
        assertThatIllegalArgumentException().isThrownBy(() -> BinaryCodec.decodePacket(ByteBuffer.wrap(tooLong)));
        assertThatIllegalArgumentException().isThrownBy(() -> BinaryCodec.encode(new Directory(1, "TOO_LONG_SYMBOL", 1, (byte) 0)));
    }
}
