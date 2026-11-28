package io.github.ahmedberrada.lob.journal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Reads the Ed25519 keys of journal seals (RS-013) from PEM files, as {@code openssl genpkey -algorithm ed25519}
 * writes the private key ({@code PRIVATE KEY}, PKCS #8) and {@code openssl pkey -pubout} the public key
 * ({@code PUBLIC KEY}, X.509).
 */
public final class SealKeys {

    private SealKeys() {
    }

    public static PrivateKey privateKey(Path pem) throws IOException {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der(pem, "PRIVATE KEY")));
        } catch (GeneralSecurityException e) {
            throw new IOException(pem + " is not an Ed25519 private key", e);
        }
    }

    public static PublicKey publicKey(Path pem) throws IOException {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der(pem, "PUBLIC KEY")));
        } catch (GeneralSecurityException e) {
            throw new IOException(pem + " is not an Ed25519 public key", e);
        }
    }

    /** A key in PEM, as the files above hold. */
    public static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static byte[] der(Path pem, String type) throws IOException {
        String text = Files.readString(pem);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int from = text.indexOf(begin);
        int to = text.indexOf(end);
        if (from < 0 || to < from) {
            throw new IOException(pem + " holds no " + type + " in PEM");
        }
        return Base64.getMimeDecoder().decode(text.substring(from + begin.length(), to));
    }
}
