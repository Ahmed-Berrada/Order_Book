package io.github.ahmedberrada.lob.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The FIX load harness frames, sums and parses FIX, keeps its rate and times every answer; against a stub acceptor. */
class FixLoadRunTest {

    private static final char SOH = FixLoadRun.SOH;

    @Test
    void framesWithTheLengthAndChecksumFixRequiresAndSplitsAStream() throws IOException {
        String framed = FixLoadRun.frame("35=0" + SOH + "49=A" + SOH);
        assertThat(framed).startsWith("8=FIX.4.4" + SOH + "9=10" + SOH);
        int sum = 0;
        String head = framed.substring(0, framed.indexOf(SOH + "10=") + 1);
        for (char c : head.toCharArray()) {
            sum += c;
        }
        assertThat(framed).endsWith("10=" + String.format("%03d", sum % 256) + SOH);

        byte[] two = (framed + FixLoadRun.frame("35=1" + SOH + "112=X" + SOH)).getBytes(StandardCharsets.US_ASCII);
        FixLoadRun.FrameReader reader = new FixLoadRun.FrameReader(new ByteArrayInputStream(two));
        assertThat(FixLoadRun.field(reader.next(), "35")).isEqualTo("0");
        String second = reader.next();
        assertThat(FixLoadRun.field(second, "112")).isEqualTo("X");
        assertThat(FixLoadRun.field(second, "999")).isNull();
        assertThat(reader.next()).isNull();
    }

    @Test
    void sendsAtTheRateItWasGivenAcrossSessionsAndTimesEveryAnswer() throws Exception {
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger logons = new AtomicInteger();
        try (ServerSocket acceptor = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread.ofPlatform().daemon(true).start(() -> {
                while (!acceptor.isClosed()) {
                    try {
                        Socket socket = acceptor.accept();
                        Thread.ofPlatform().daemon(true).start(() -> serve(socket, cancels, logons));
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            FixLoadRun.Result result = FixLoadRun.run("127.0.0.1", acceptor.getLocalPort(), "AAPL",
                    List.of("MEMBER1", "MEMBER2:secret"), 400, Duration.ofSeconds(2));

            assertThat(logons.get()).isEqualTo(2);
            assertThat(result.sessions()).isEqualTo(2);
            assertThat(result.sent()).isBetween(780L, 800L);
            assertThat(result.unanswered()).isZero();
            assertThat(result.latency().getTotalCount()).isEqualTo(result.sent());
            assertThat(result.answers().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(result.sent());
            assertThat(cancels.get()).as("about 30% cancels of acknowledged resting orders").isBetween(150, 300);
            assertThat(result.row()).contains("| 400 | 2 |");
        }
    }

    /** Answers a Logon, a TestRequest once, and every order with an ExecutionReport. */
    private static void serve(Socket socket, AtomicInteger cancels, AtomicInteger logons) {
        try (socket) {
            OutputStream out = socket.getOutputStream();
            FixLoadRun.FrameReader reader = new FixLoadRun.FrameReader(socket.getInputStream());
            int seq = 0;
            for (String message = reader.next(); message != null; message = reader.next()) {
                String type = FixLoadRun.field(message, "35");
                String reply = switch (type) {
                    case "A" -> {
                        logons.incrementAndGet();
                        yield "35=A" + SOH + "98=0" + SOH + "108=30" + SOH + "1=1" + SOH;
                    }
                    case "D" -> "35=8" + SOH + "11=" + FixLoadRun.field(message, "11") + SOH + "150="
                            + ("1".equals(FixLoadRun.field(message, "40")) ? "F" : "0") + SOH;
                    case "F" -> {
                        cancels.incrementAndGet();
                        yield "35=8" + SOH + "11=" + FixLoadRun.field(message, "11") + SOH + "150=4" + SOH;
                    }
                    default -> null;
                };
                if (reply != null) {
                    out.write(FixLoadRun.frame(reply + "34=" + (++seq) + SOH).getBytes(StandardCharsets.US_ASCII));
                    if (seq == 1) {
                        out.write(FixLoadRun.frame("35=1" + SOH + "112=T" + SOH).getBytes(StandardCharsets.US_ASCII));
                    }
                    out.flush();
                }
            }
        } catch (IOException e) {
            // the client went away
        }
    }
}
