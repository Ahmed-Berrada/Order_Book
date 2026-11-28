package io.github.ahmedberrada.lob.fix;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * TLS for the FIX acceptor (AC-010, AC-011): the venue's key store, and with {@code needClientAuth} the trust store
 * that members' certificates must chain to. TLS 1.2 and 1.3 only.
 *
 * @param keyStore           the venue's key and certificate (PKCS12 or JKS)
 * @param trustStore         the CAs of members' certificates; required with {@code needClientAuth}, else may be null
 * @param needClientAuth     whether a connection must present a certificate from a trusted CA (mutual TLS)
 * @param bindCertificateToCompId with mutual TLS, whether the certificate's common name must be the CompID the
 *                           session logs on as (AC-013)
 */
public record FixTls(Path keyStore, String keyStorePassword, String keyStoreType, Path trustStore,
        String trustStorePassword, String trustStoreType, boolean needClientAuth, boolean bindCertificateToCompId) {

    /** With mutual TLS, a certificate is bound to its CompID (AC-013). */
    public FixTls(Path keyStore, String keyStorePassword, String keyStoreType, Path trustStore,
            String trustStorePassword, String trustStoreType, boolean needClientAuth) {
        this(keyStore, keyStorePassword, keyStoreType, trustStore, trustStorePassword, trustStoreType, needClientAuth,
                needClientAuth);
    }

    /** The protocols offered: nothing older than TLS 1.2. */
    static final String PROTOCOLS = "TLSv1.3,TLSv1.2";

    public FixTls {
        Objects.requireNonNull(keyStore, "keyStore: the venue's own, never QuickFIX/J's default");
        Objects.requireNonNull(keyStorePassword, "keyStorePassword");
        Objects.requireNonNull(keyStoreType, "keyStoreType");
        if (!Files.isReadable(keyStore)) {
            throw new IllegalArgumentException("FIX key store not readable: " + keyStore);
        }
        if (bindCertificateToCompId && !needClientAuth) {
            throw new IllegalArgumentException("binding certificates to CompIDs needs mutual TLS");
        }
        if (needClientAuth && trustStore == null) {
            throw new IllegalArgumentException("mutual TLS needs a trust store of the members' CAs");
        }
        if (trustStore != null) {
            Objects.requireNonNull(trustStorePassword, "trustStorePassword");
            Objects.requireNonNull(trustStoreType, "trustStoreType");
            if (!Files.isReadable(trustStore)) {
                throw new IllegalArgumentException("FIX trust store not readable: " + trustStore);
            }
        }
    }

    @Override
    public String toString() {                        // never the passwords
        return "FixTls[keyStore=" + keyStore + ", trustStore=" + trustStore + ", needClientAuth=" + needClientAuth
                + ", bindCertificateToCompId=" + bindCertificateToCompId + "]";
    }
}
