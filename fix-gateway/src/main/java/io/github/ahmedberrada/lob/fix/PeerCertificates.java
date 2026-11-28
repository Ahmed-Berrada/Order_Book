package io.github.ahmedberrada.lob.fix;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.ssl.SslFilter;

/**
 * Binds a member's certificate to its CompID (AC-013): keeps each open connection by its remote address, the
 * address a FIX session's responder reports, so that at Logon the certificate the connection presented can be
 * checked to name the CompID it logs on as. A certificate from a trusted CA proves only that its holder is a member;
 * this makes it prove which one.
 */
final class PeerCertificates extends IoFilterAdapter {

    private final Map<String, IoSession> open = new ConcurrentHashMap<>();

    @Override
    public void sessionOpened(NextFilter next, IoSession session) throws Exception {
        if (session.getRemoteAddress() != null) {
            open.put(session.getRemoteAddress().toString(), session);
        }
        next.sessionOpened(session);
    }

    @Override
    public void sessionClosed(NextFilter next, IoSession session) throws Exception {
        if (session.getRemoteAddress() != null) {
            open.remove(session.getRemoteAddress().toString(), session);
        }
        next.sessionClosed(session);
    }

    /** Whether the connection from this address presented a certificate whose one common name is the CompID. */
    boolean names(String remoteAddress, String compId) {
        IoSession session = remoteAddress == null ? null : open.get(remoteAddress);
        Object secured = session == null ? null : session.getAttribute(SslFilter.SSL_SECURED);
        if (!(secured instanceof SSLSession ssl)) {
            return false;
        }
        try {
            Certificate[] chain = ssl.getPeerCertificates();
            return chain.length > 0 && chain[0] instanceof X509Certificate certificate
                    && List.of(compId).equals(commonNames(certificate));
        } catch (SSLPeerUnverifiedException e) {
            return false;
        }
    }

    /** The common names in a certificate's subject: a subject with two names none, or one, is ambiguous. */
    static List<String> commonNames(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        try {
            LdapName subject = new LdapName(certificate.getSubjectX500Principal().getName(X500Principal.RFC2253));
            for (Rdn rdn : subject.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    names.add(rdn.getValue().toString());
                }
            }
        } catch (InvalidNameException e) {
            return List.of();
        }
        return names;
    }
}
