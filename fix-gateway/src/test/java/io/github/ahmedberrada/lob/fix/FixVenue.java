package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.journal.FsyncPolicy;
import io.github.ahmedberrada.lob.journal.JournalOptions;
import io.github.ahmedberrada.lob.journal.TimeSource;
import io.github.ahmedberrada.lob.service.InstrumentConfig;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;

/** A complete venue for gateway tests: journals, service and FIX acceptor in a temporary directory. */
final class FixVenue implements AutoCloseable {

    static final InstrumentConfig AAPL =
            new InstrumentConfig("AAPL", new BigDecimal("0.01"), 1_000, new BigDecimal("10000.00"));

    final MatchingService service;
    final FixGateway gateway;
    final int port;

    FixVenue(Path directory, int port) throws Exception {
        this.port = port;
        this.service = MatchingService.start(directory.resolve("journals"), List.of(AAPL),
                new JournalOptions(FsyncPolicy.OS, 0), TimeSource.system(), 1_000);
        this.gateway = FixGateway.start(service,
                new FixGatewayConfig(port, "LOB", List.of("MEMBER1", "MEMBER2"), directory.resolve("fix-store")));
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() throws IOException {
        gateway.close();
        service.close();
    }
}
