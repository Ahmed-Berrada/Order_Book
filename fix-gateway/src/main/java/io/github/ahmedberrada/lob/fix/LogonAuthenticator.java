package io.github.ahmedberrada.lob.fix;

/**
 * Decides whether a FIX session may log on, from its CompID and the {@code Password} (tag 554) of its Logon
 * message. The gateway does not know how credentials are stored: the application supplies the check, so
 * that this module stays free of any framework (ADR-0006).
 */
@FunctionalInterface
public interface LogonAuthenticator {

    /** Lets every session in, as before authentication existed. For tests and closed development setups only. */
    LogonAuthenticator ALLOW_ALL = (compId, password) -> true;

    /**
     * @param password the Logon's {@code Password}, or {@code null} if it carried none
     * @return whether the session may log on
     */
    boolean authenticate(String compId, String password);
}
