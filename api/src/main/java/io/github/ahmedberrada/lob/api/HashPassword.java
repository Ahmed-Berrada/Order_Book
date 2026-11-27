package io.github.ahmedberrada.lob.api;

import org.springframework.security.crypto.factory.PasswordEncoderFactories;

/**
 * Prints the salted hash of a password in the form the configuration wants (AC-002):
 * {@code java -cp api.jar io.github.ahmedberrada.lob.api.HashPassword 'the password'}.
 * Prefer a shell that does not keep the command in its history.
 */
public final class HashPassword {

    private HashPassword() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        if (args.length != 1) {
            err.println("usage: HashPassword <password>");
            return 2;
        }
        out.println(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(args[0]));
        return 0;
    }
}
