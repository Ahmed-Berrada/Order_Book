package io.github.ahmedberrada.lob.marketdata.binary;

import java.util.ArrayList;
import java.util.List;

/** The last {@code capacity} encoded messages, by stream sequence number, for retransmission (BF-004). */
final class MessageRing {

    private final byte[][] messages;
    private long last;                      // highest sequence stored, 0 if none

    MessageRing(int capacity) {
        this.messages = new byte[capacity][];
    }

    synchronized void put(long sequence, byte[] message) {
        messages[(int) ((sequence - 1) % messages.length)] = message;
        last = sequence;
    }

    /** The messages {@code from} to {@code from + count - 1}, or {@code null} if any is outside the window. */
    synchronized List<byte[]> get(long from, int count) {
        long oldest = Math.max(1, last - messages.length + 1);
        if (count < 1 || from < oldest || from + count - 1 > last) {
            return null;
        }
        List<byte[]> range = new ArrayList<>(count);
        for (long sequence = from; sequence < from + count; sequence++) {
            range.add(messages[(int) ((sequence - 1) % messages.length)]);
        }
        return range;
    }
}
