package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.core.SelfTradeInstruction;
import io.github.ahmedberrada.lob.service.MatchingService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        return start(service, config, LogonAuthenticator.ALLOW_ALL);
    }

    /** Starts accepting member connections; a session logs on only if the authenticator accepts it. */
    public static FixGateway start(MatchingService service, FixGatewayConfig config,
            LogonAuthenticator authenticator) throws ConfigError {
        return start(service, config, authenticator, null);
    }

    /** Like {@link #start(MatchingService, FixGatewayConfig, LogonAuthenticator)}, over TLS unless {@code tls} is null. */
    public static FixGateway start(MatchingService service, FixGatewayConfig config,
            LogonAuthenticator authenticator, FixTls tls) throws ConfigError {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(authenticator, "authenticator");
        SessionSettings settings = settings(config, tls);
        List<SessionID> dropCopies = config.dropCopyCompIds().stream()
                .map(target -> new SessionID(BEGIN_STRING, config.senderCompId(), target)).toList();
        Map<SessionID, SelfTradeInstruction> selfTrade = new HashMap<>();
        for (String member : config.memberCompIds()) {
            selfTrade.put(new SessionID(BEGIN_STRING, config.senderCompId(), member), new SelfTradeInstruction(
                    config.memberId(member), config.selfTradeMode()));
        }
        FixApplication application = new FixApplication(service, dropCopies, selfTrade, authenticator);
        service.addListener(application);
        ThreadedSocketAcceptor acceptor = new ThreadedSocketAcceptor(application, new FileStoreFactory(settings),
                settings, new SLF4JLogFactory(settings), new DefaultMessageFactory());
        if (tls != null && tls.bindCertificateToCompId()) {              // AC-013
            PeerCertificates peers = new PeerCertificates();
            acceptor.setIoFilterChainBuilder(chain -> chain.addLast("lob-peer-certificates", peers));
            application.bindCertificates(peers);
        }
        acceptor.start();
        return new FixGateway(acceptor);
    }

    static SessionSettings settings(FixGatewayConfig config) {
        return settings(config, null);
    }

    static SessionSettings settings(FixGatewayConfig config, FixTls tls) {
        SessionSettings settings = new SessionSettings();
        if (tls != null) {                                             // AC-010, AC-011
            settings.setString("SocketUseSSL", "Y");
            settings.setString("SocketKeyStore", tls.keyStore().toAbsolutePath().toString());
            settings.setString("SocketKeyStorePassword", tls.keyStorePassword());
            settings.setString("KeyStoreType", tls.keyStoreType());
            settings.setString("EnabledProtocols", FixTls.PROTOCOLS);
            if (tls.trustStore() != null) {
                settings.setString("SocketTrustStore", tls.trustStore().toAbsolutePath().toString());
                settings.setString("SocketTrustStorePassword", tls.trustStorePassword());
                settings.setString("TrustStoreType", tls.trustStoreType());
            }
            settings.setString("NeedClientAuth", tls.needClientAuth() ? "Y" : "N");
        }
        settings.setString("ConnectionType", "acceptor");
        settings.setString("BeginString", BEGIN_STRING);
        settings.setString("SenderCompID", config.senderCompId());
        settings.setString("SocketAcceptAddress", config.bindAddress());
        settings.setLong("SocketAcceptPort", config.port());
        settings.setString("NonStopSession", "Y");               // sequence numbers persist; no daily reset yet
        settings.setString("UseDataDictionary", "Y");
        settings.setString("DataDictionary", "FIX44.xml");
        settings.setString("FileStorePath", config.storeDirectory().toString());
        settings.setString("SLF4JLogHeartbeats", "N");
        List<String> sessions = new ArrayList<>(config.memberCompIds());
        sessions.addAll(config.dropCopyCompIds());
        for (String member : sessions) {
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
