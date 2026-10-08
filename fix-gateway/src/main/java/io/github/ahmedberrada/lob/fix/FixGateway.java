package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.service.MatchingService;
import java.util.Objects;
import quickfix.Acceptor;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FileStoreFactory;
import quickfix.SLF4JLogFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.ThreadedSocketAcceptor;

/**
 * The FIX acceptor: one session per member, persistent sequence numbers, messages validated against
 * the FIX 4.4 data dictionary (ADR-0006).
 */
public final class FixGateway implements AutoCloseable {

    static final String BEGIN_STRING = "FIX.4.4";

    private final Acceptor acceptor;

    private FixGateway(Acceptor acceptor) {
        this.acceptor = acceptor;
    }

    /** Starts accepting member connections. */
    public static FixGateway start(MatchingService service, FixGatewayConfig config) throws ConfigError {
        Objects.requireNonNull(service, "service");
        SessionSettings settings = settings(config);
        FixApplication application = new FixApplication(service);
        service.addListener(application);
        Acceptor acceptor = new ThreadedSocketAcceptor(application, new FileStoreFactory(settings), settings,
                new SLF4JLogFactory(settings), new DefaultMessageFactory());
        acceptor.start();
        return new FixGateway(acceptor);
    }

    static SessionSettings settings(FixGatewayConfig config) {
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "acceptor");
        settings.setString("BeginString", BEGIN_STRING);
        settings.setString("SenderCompID", config.senderCompId());
        settings.setLong("SocketAcceptPort", config.port());
        settings.setString("NonStopSession", "Y");               // sequence numbers persist; no daily reset yet
        settings.setString("UseDataDictionary", "Y");
        settings.setString("DataDictionary", "FIX44.xml");
        settings.setString("FileStorePath", config.storeDirectory().toString());
        settings.setString("SLF4JLogHeartbeats", "N");
        for (String member : config.memberCompIds()) {
            SessionID session = new SessionID(BEGIN_STRING, config.senderCompId(), member);
            settings.setString(session, "TargetCompID", member);
        }
        return settings;
    }

    /** Logs every session out and stops accepting connections. */
    @Override
    public void close() {
        acceptor.stop();
    }
}
