package io.github.ahmedberrada.lob.bench;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

/**
 * An end-to-end load test of a running venue over FIX 4.4 (docs/performance.md): one session per member CompID, each
 * sending orders, cancels and market orders at a fixed rate whatever the answers (an open loop), through the FIX
 * engine, the queue, the journal and matching.
 *
 * <p>A request's latency runs from the moment it was due to the first message the venue sends about it: the
 * ExecutionReport acknowledging, filling or rejecting it, or a reject. A venue that stalls makes every request
 * scheduled behind the stall wait, and that waiting is counted (no coordinated omission).
 *
 * <p>The client speaks FIX itself, a few hundred lines rather than a FIX engine, so that what is measured is the
 * venue's: it frames, sums and parses, answers test requests, and does nothing else.
 *
 * <p>Usage: {@code java -cp engine-bench/target/benchmarks.jar io.github.ahmedberrada.lob.bench.FixLoadRun
 * 127.0.0.1:9878 [rate per second, all sessions] [seconds] [symbol] [CompID[:password],...]}.
 */
public final class FixLoadRun {

    static final char SOH = '\u0001';
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss.SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    /** What one run measured. */
    record Result(int targetRate, int sessions, double seconds, long sent, Map<String, Long> answers, long unanswered,
            Histogram latency) {

        double achievedRate() {
            return sent / seconds;
        }

        String row() {
            return String.format(Locale.ROOT, "| %,d | %d | %,.0f | %s | %s | %s | %s | %s | %d |", targetRate, sessions,
                    achievedRate(), micros(latency.getValueAtPercentile(50)), micros(latency.getValueAtPercentile(99)),
                    micros(latency.getValueAtPercentile(99.9)), micros(latency.getMaxValue()), answers, unanswered);
        }

        private static String micros(long nanos) {
            return nanos >= 1_000_000 ? String.format(Locale.ROOT, "%.1f ms", nanos / 1e6)
                    : String.format(Locale.ROOT, "%d µs", nanos / 1_000);
        }
    }

    private FixLoadRun() {
    }

    public static void main(String[] args) throws Exception {
        String[] address = (args.length > 0 ? args[0] : "127.0.0.1:9878").split(":");
        int rate = args.length > 1 ? Integer.parseInt(args[1]) : 1_000;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        String symbol = args.length > 3 ? args[3] : "AAPL";
        List<String> sessions = List.of((args.length > 4 ? args[4] : "MEMBER1,MEMBER2").split(","));
        String host = address[0];
        int port = Integer.parseInt(address[1]);
        run(host, port, symbol, sessions, rate, Duration.ofSeconds(Math.max(5, seconds / 5)));       // warm-up
        System.out.println("| Target /s | Sessions | Achieved /s | p50 | p99 | p99.9 | max | Answers | Unanswered |");
        System.out.println("|---:|---:|---:|---:|---:|---:|---:|---|---:|");
        System.out.println(run(host, port, symbol, sessions, rate, Duration.ofSeconds(seconds)).row());
    }

    /**
     * Logs on every session, sends {@code rate} requests a second across them for {@code duration}, then waits up to
     * 30 s for the answers and logs out.
     *
     * @param sessions member CompIDs, each optionally {@code CompID:password}
     */
    static Result run(String host, int port, String symbol, List<String> sessions, int rate, Duration duration)
            throws IOException, InterruptedException {
        Recorder recorder = new Recorder(60_000_000_000L, 3);
        Map<String, AtomicLong> answers = new ConcurrentHashMap<>();
        List<Session> open = new ArrayList<>();
        try {
            for (String session : sessions) {
                String[] parts = session.split(":", 2);
                open.add(Session.logon(host, port, parts[0], parts.length > 1 ? parts[1] : null, recorder, answers));
            }
            int perSession = Math.max(1, rate / open.size());
            CountDownLatch done = new CountDownLatch(open.size());
            long start = System.nanoTime();
            long[] sent = new long[open.size()];
            for (int s = 0; s < open.size(); s++) {
                Session session = open.get(s);
                int index = s;
                Thread.ofPlatform().name("fix-load-" + session.compId).start(() -> {
                    try {
                        sent[index] = session.sendFor(symbol, perSession, start, duration, new SplittableRandom(42 + index));
                    } catch (IOException e) {
                        session.failed = e;
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
            long sendingEnded = System.nanoTime();
            long total = 0;
            for (int s = 0; s < open.size(); s++) {
                total += sent[s];
                if (open.get(s).failed != null) {
                    throw open.get(s).failed;
                }
            }
            long deadline = sendingEnded + TimeUnit.SECONDS.toNanos(30);
            while (open.stream().mapToLong(Session::outstanding).sum() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Map<String, Long> counts = new TreeMap<>();
            answers.forEach((kind, count) -> counts.put(kind, count.get()));
            return new Result(rate, open.size(), (sendingEnded - start) / 1e9, total, counts,
                    open.stream().mapToLong(Session::outstanding).sum(), recorder.getIntervalHistogram());
        } finally {
            for (Session session : open) {
                session.close();
            }
        }
    }

    /** One FIX session: a writer (the caller's thread) and a reader thread. */
    static final class Session implements AutoCloseable {

        private final Socket socket;
        private final OutputStream out;
        private final String compId;
        private final Recorder recorder;
        private final Map<String, AtomicLong> answers;
        private final Map<String, Long> due = new ConcurrentHashMap<>();        // ClOrdID → when it was due
        private final ConcurrentLinkedQueue<String[]> resting = new ConcurrentLinkedQueue<>();   // ClOrdID, side
        private final CountDownLatch loggedOn = new CountDownLatch(1);
        private final Thread reader;
        private long sequence;
        private long nextId;
        private final String run = Long.toString(System.currentTimeMillis(), 36);
        private volatile IOException failed;

        private Session(Socket socket, String compId, Recorder recorder, Map<String, AtomicLong> answers)
                throws IOException {
            this.socket = socket;
            this.out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
            this.compId = compId;
            this.recorder = recorder;
            this.answers = answers;
            this.reader = Thread.ofPlatform().daemon(true).name("fix-read-" + compId).start(this::read);
        }

        static Session logon(String host, int port, String compId, String password, Recorder recorder,
                Map<String, AtomicLong> answers) throws IOException, InterruptedException {
            Socket socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            Session session = new Session(socket, compId, recorder, answers);
            StringBuilder logon = new StringBuilder("98=0").append(SOH).append("108=30").append(SOH).append("141=Y")
                    .append(SOH);
            if (password != null) {
                logon.append("554=").append(password).append(SOH);
            }
            session.send("A", logon);
            session.out.flush();
            if (!session.loggedOn.await(10, TimeUnit.SECONDS)) {
                session.close();
                throw new IOException(compId + ": no Logon answer within 10 s");
            }
            return session;
        }

        /** Sends at {@code rate} a second from {@code start} for {@code duration}; returns how many it sent. */
        long sendFor(String symbol, int rate, long start, Duration duration, SplittableRandom random) throws IOException {
            long interval = TimeUnit.SECONDS.toNanos(1) / rate;
            long end = start + duration.toNanos();
            long sent = 0;
            for (long at = start; at < end; at += interval) {
                long wait = at - System.nanoTime();
                if (wait > 0) {
                    flush();                                     // nothing is due before then: send what is buffered
                    LockSupport.parkNanos(wait);
                }
                String clOrdId = run + "-" + (++nextId);       // unique per session across runs (OE-001, FIX 11)
                due.put(clOrdId, at);
                String[] message = next(random, symbol, clOrdId);
                synchronized (this) {
                    send(message[0], message[1]);
                }
                sent++;
            }
            flush();
            return sent;
        }

        private synchronized void flush() throws IOException {
            out.flush();
        }

        /** 60% resting limit orders around 100.00, 30% cancels of orders it left resting, 10% market orders. */
        private String[] next(SplittableRandom random, String symbol, String clOrdId) {
            int dice = random.nextInt(100);
            String now = TIME.format(Instant.now());
            String[] cancel = dice < 30 ? resting.poll() : null;
            StringBuilder body = new StringBuilder();
            if (cancel != null) {
                body.append("41=").append(cancel[0]).append(SOH).append("11=").append(clOrdId).append(SOH)
                        .append("55=").append(symbol).append(SOH).append("54=").append(cancel[1]).append(SOH)
                        .append("60=").append(now).append(SOH);
                return new String[] {"F", body.toString()};
            }
            boolean buy = random.nextBoolean();
            String side = buy ? "1" : "2";
            body.append("11=").append(clOrdId).append(SOH).append("55=").append(symbol).append(SOH).append("54=")
                    .append(side).append(SOH).append("60=").append(now).append(SOH);
            if (dice >= 90) {
                body.append("38=1").append(SOH).append("40=1").append(SOH);
            } else {
                int ticks = 10_000 + (buy ? -1 : 1) * random.nextInt(1, 200);          // never crossing: they rest
                body.append("38=").append(random.nextInt(1, 10)).append(SOH).append("40=2").append(SOH).append("44=")
                        .append(ticks / 100).append('.').append(String.format(Locale.ROOT, "%02d", ticks % 100))
                        .append(SOH).append("59=1").append(SOH);
                pending.put(clOrdId, side);
            }
            return new String[] {"D", body.toString()};
        }

        private final Map<String, String> pending = new ConcurrentHashMap<>();   // limit orders not yet acknowledged

        private void send(String type, CharSequence body) throws IOException {
            String header = "35=" + type + SOH + "49=" + compId + SOH + "56=LOB" + SOH + "34=" + (++sequence) + SOH
                    + "52=" + TIME.format(Instant.now()) + SOH;
            out.write(frame(header + body).getBytes(StandardCharsets.US_ASCII));
        }

        long outstanding() {
            return due.size();
        }

        private void read() {
            try (InputStream in = socket.getInputStream()) {
                FrameReader frames = new FrameReader(in);
                for (String message = frames.next(); message != null; message = frames.next()) {
                    handle(message);
                }
            } catch (IOException e) {
                // the session ended
            }
        }

        private void handle(String message) throws IOException {
            String type = field(message, "35");
            switch (type) {
                case "A" -> loggedOn.countDown();
                case "1" -> {                                    // TestRequest: answer it, or the venue logs us out
                    synchronized (this) {
                        send("0", "112=" + field(message, "112") + SOH);
                        out.flush();
                    }
                }
                case "8" -> answered(field(message, "11"), "8".equals(field(message, "150"))
                        ? "rejected: " + field(message, "58") : "150=" + field(message, "150"),
                        "0".equals(field(message, "150")) ? field(message, "11") : null);
                case "9" -> answered(field(message, "11"), "cancel reject", null);
                case "3", "j" -> answers.computeIfAbsent("35=" + type, k -> new AtomicLong()).incrementAndGet();
                default -> {
                    // heartbeats, trading session status, logout
                }
            }
        }

        private void answered(String clOrdId, String kind, String acknowledged) {
            Long scheduled = clOrdId == null ? null : due.remove(clOrdId);
            if (scheduled != null) {                             // the first answer to a request: its latency
                recorder.recordValue(Math.max(0, System.nanoTime() - scheduled));
                answers.computeIfAbsent(kind, k -> new AtomicLong()).incrementAndGet();
            }
            if (acknowledged != null) {
                String side = pending.remove(acknowledged);
                if (side != null) {
                    resting.add(new String[] {acknowledged, side});
                }
            }
        }

        @Override
        public void close() throws IOException {
            try {
                synchronized (this) {
                    send("5", "");
                    out.flush();
                }
            } catch (IOException e) {
                // already gone
            }
            socket.close();
            try {
                reader.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** {@code 8=FIX.4.4|9=<length>|<header and body>10=<checksum>|}. */
    static String frame(String headerAndBody) {
        String head = "8=FIX.4.4" + SOH + "9=" + headerAndBody.length() + SOH + headerAndBody;
        int sum = 0;
        for (int i = 0; i < head.length(); i++) {
            sum += head.charAt(i);
        }
        return head + "10=" + String.format(Locale.ROOT, "%03d", sum % 256) + SOH;
    }

    /** The first value of a tag in a message, or null. */
    static String field(String message, String tag) {
        String key = SOH + tag + "=";
        int at = message.indexOf(key);
        if (at < 0) {
            return null;
        }
        int from = at + key.length();
        int to = message.indexOf(SOH, from);
        return message.substring(from, to < 0 ? message.length() : to);
    }

    /** Splits a FIX byte stream into messages, each ending with its checksum field. */
    static final class FrameReader {

        private final InputStream in;
        private final StringBuilder buffer = new StringBuilder();
        private final byte[] chunk = new byte[1 << 16];

        FrameReader(InputStream in) {
            this.in = in;
        }

        /** The next whole message, with a leading SOH so every field starts with one; null at the end. */
        String next() throws IOException {
            while (true) {
                int end = buffer.indexOf(SOH + "10=");
                if (end >= 0) {
                    int close = buffer.indexOf(String.valueOf(SOH), end + 1);
                    if (close >= 0) {
                        String message = SOH + buffer.substring(0, close + 1);
                        buffer.delete(0, close + 1);
                        return message;
                    }
                }
                int read = in.read(chunk);
                if (read < 0) {
                    return null;
                }
                buffer.append(new String(chunk, 0, read, StandardCharsets.US_ASCII));
            }
        }
    }
}
