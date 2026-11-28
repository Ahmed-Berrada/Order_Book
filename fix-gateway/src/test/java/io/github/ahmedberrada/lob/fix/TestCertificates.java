package io.github.ahmedberrada.lob.fix;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Key and trust stores for TLS tests, made with the JDK's keytool: self-signed, so each is its own CA. */
public final class TestCertificates {

    public static final String PASSWORD = "changeit-test";

    private TestCertificates() {
    }

    /** A PKCS12 key store with a fresh EC key and a self-signed certificate valid for localhost and 127.0.0.1. */
    public static Path keyStore(Path directory, String name) throws IOException, InterruptedException {
        Path store = directory.resolve(name + ".p12");
        keytool("-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "2",
                "-dname", "CN=" + name, "-ext", "san=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD);
        return store;
    }

    /** A PKCS12 trust store holding the certificates of the given key stores. */
    public static Path trustStore(Path directory, String name, Path... keyStores) throws IOException, InterruptedException {
        Path store = directory.resolve(name + ".p12");
        for (Path keyStore : keyStores) {
            String alias = keyStore.getFileName().toString().replace(".p12", "");
            Path certificate = directory.resolve(alias + ".crt");
            keytool("-exportcert", "-rfc", "-alias", alias, "-keystore", keyStore.toString(), "-storetype", "PKCS12",
                    "-storepass", PASSWORD, "-file", certificate.toString());
            keytool("-importcert", "-noprompt", "-alias", alias, "-file", certificate.toString(), "-storetype", "PKCS12",
                    "-keystore", store.toString(), "-storepass", PASSWORD);
        }
        return store;
    }

    private static void keytool(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("keytool failed: " + String.join(" ", args) + "\n" + output);
        }
    }
}
