package io.github.ahmedberrada.lob.fix;

import io.github.ahmedberrada.lob.service.MatchingService;
import quickfix.Application;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.UnsupportedMessageType;

/**
 * Application layer of the gateway. Session-level messages are handled by QuickFIX/J; application
 * messages are translated to commands (ADR-0006).
 */
final class FixApplication implements Application {

    private final MatchingService service;

    FixApplication(MatchingService service) {
        this.service = service;
    }

    @Override
    public void fromApp(Message message, SessionID session) throws UnsupportedMessageType {
        throw new UnsupportedMessageType();
    }

    @Override
    public void onCreate(SessionID session) {
    }

    @Override
    public void onLogon(SessionID session) {
    }

    @Override
    public void onLogout(SessionID session) {
    }

    @Override
    public void toAdmin(Message message, SessionID session) {
    }

    @Override
    public void fromAdmin(Message message, SessionID session) {
    }

    @Override
    public void toApp(Message message, SessionID session) {
    }

    MatchingService service() {
        return service;
    }
}
