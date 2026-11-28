package io.github.ahmedberrada.lob.fix;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import quickfix.Application;
import quickfix.ConfigError;
import quickfix.DefaultMessageFactory;
import quickfix.FieldNotFound;
import quickfix.FileStoreFactory;
import quickfix.Message;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.MsgType;

/**
 * A member's FIX engine, as used in certification: a QuickFIX/J initiator that records every message
 * it receives. Its message store is on disk, so it can disconnect and reconnect with its sequence
 * numbers intact.
 */
public final class FixTestClient implements Application, AutoCloseable {

    private final SessionID session;
    private final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
    /** TradingSessionStatus messages (FS-001, FS-002), kept apart so that order flow tests need not skip them. */
    private final BlockingQueue<Message> statuses = new LinkedBlockingQueue<>();
    private volatile CountDownLatch loggedOn = new CountDownLatch(1);

    public FixTestClient(String compId, int port, Path storeDirectory) throws Exception {
        session = new SessionID(FixGateway.BEGIN_STRING, compId, "LOB");
        SessionSettings settings = new SessionSettings();
        settings.setString("ConnectionType", "initiator");
        settings.setString("BeginString", FixGateway.BEGIN_STRING);
        settings.setString("SocketConnectHost", "localhost");
        settings.setLong("SocketConnectPort", port);
        settings.setLong("HeartBtInt", 30);
        settings.setLong("ReconnectInterval", 1);
        settings.setString("NonStopSession", "Y");
        settings.setString("UseDataDictionary", "Y");
        settings.setString("DataDictionary", "FIX44.xml");
        settings.setString("FileStorePath", storeDirectory.resolve(compId).toString());
        settings.setString("ScreenLogShowEvents", "N");
        settings.setString("ScreenLogShowIncoming", "N");
        settings.setString("ScreenLogShowOutgoing", "N");
        settings.setString(session, "SenderCompID", compId);
        settings.setString(session, "TargetCompID", "LOB");
        this.settings = settings;
    }

    private final SessionSettings settings;
    private SocketInitiator initiator;

    /**
     * Connects over TLS, trusting the certificates of {@code trustStore}, and presenting {@code keyStore}'s if not
     * null (mutual TLS).
     */
    public FixTestClient withTls(Path trustStore, Path keyStore) {
        settings.setString(session, "SocketUseSSL", "Y");
        settings.setString(session, "SocketTrustStore", trustStore.toAbsolutePath().toString());
        settings.setString(session, "SocketTrustStorePassword", TestCertificates.PASSWORD);
        settings.setString(session, "TrustStoreType", "PKCS12");
        settings.setString(session, "EnabledProtocols", "TLSv1.3,TLSv1.2");
        if (keyStore != null) {
            settings.setString(session, "SocketKeyStore", keyStore.toAbsolutePath().toString());
            settings.setString(session, "SocketKeyStorePassword", TestCertificates.PASSWORD);
            settings.setString(session, "KeyStoreType", "PKCS12");
        }
        return this;
    }

    private SocketInitiator initiator() throws ConfigError {
        if (initiator == null) {
            initiator = new SocketInitiator(this, new FileStoreFactory(settings), settings,
                    new ScreenLogFactory(settings), new DefaultMessageFactory());
        }
        return initiator;
    }

    private volatile String password;

    /** The Password (tag 554) sent in the Logon message. */
    public FixTestClient withPassword(String password) {
        this.password = password;
        return this;
    }

    /** Connects; whether the venue accepted the logon within the time given. */
    public boolean tryLogon(long millis) throws Exception {
        loggedOn = new CountDownLatch(1);
        initiator().start();
        return loggedOn.await(millis, TimeUnit.MILLISECONDS);
    }

    /** Connects and waits until the venue has accepted the logon. */
    public FixTestClient logon() throws Exception {
        loggedOn = new CountDownLatch(1);
        initiator().start();
        if (!loggedOn.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError(session + " could not log on");
        }
        return this;
    }

    public boolean isLoggedOn() {
        Session s = Session.lookupSession(session);
        return s != null && s.isLoggedOn();
    }

    public void send(Message message) throws SessionNotFound {
        if (!Session.sendToTarget(message, session)) {
            throw new AssertionError("could not send " + message);
        }
    }

    /** Sends a message and returns the next one received. */
    public Message next(Message toSend) throws Exception {
        send(toSend);
        return next();
    }

    /** Next application or reject message, failing after a few seconds. */
    public Message next() throws InterruptedException {
        Message message = received.poll(5, TimeUnit.SECONDS);
        if (message == null) {
            throw new AssertionError(session + " received nothing");
        }
        return message;
    }

    /** Next TradingSessionStatus, failing after a few seconds. */
    public Message nextStatus() throws InterruptedException {
        Message message = statuses.poll(5, TimeUnit.SECONDS);
        if (message == null) {
            throw new AssertionError(session + " received no TradingSessionStatus");
        }
        return message;
    }

    /** Asserts that no TradingSessionStatus arrives for a short while. */
    public void expectNoStatus(Duration wait) throws InterruptedException {
        Message message = statuses.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (message != null) {
            throw new AssertionError(session + " unexpectedly received " + message);
        }
    }

    /** Asserts that nothing arrives for a short while. */
    public void expectNothing(Duration wait) throws InterruptedException {
        Message message = received.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
        if (message != null) {
            throw new AssertionError(session + " unexpectedly received " + message);
        }
    }

    public static String type(Message message) throws FieldNotFound {
        return message.getHeader().getString(MsgType.FIELD);
    }

    /** Disconnects, keeping the message store, so a later {@link #logon()} resumes the session. */
    public void disconnect() {
        if (initiator != null) {
            initiator.stop(true);
        }
    }

    @Override
    public void close() {
        if (initiator != null) {
            initiator.stop(true);
        }
    }

    @Override
    public void onLogon(SessionID sessionId) {
        loggedOn.countDown();
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) {
        try {
            if (MsgType.TRADING_SESSION_STATUS.equals(type(message))) {
                statuses.add(message);
                return;
            }
        } catch (FieldNotFound e) {
            throw new IllegalStateException(e);
        }
        received.add(message);
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) throws FieldNotFound {
        if (MsgType.REJECT.equals(type(message))) {
            received.add(message);
        }
    }

    @Override
    public void onCreate(SessionID sessionId) {
    }

    @Override
    public void onLogout(SessionID sessionId) {
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        try {
            if (password != null && quickfix.field.MsgType.LOGON.equals(message.getHeader().getString(quickfix.field.MsgType.FIELD))) {
                message.setString(quickfix.field.Password.FIELD, password);
            }
        } catch (quickfix.FieldNotFound e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionId) {
    }
}
