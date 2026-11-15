package io.github.ahmedberrada.lob.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Points the venue's journals at a fresh temporary directory for each test class. */
final class TemporaryVenue {

    private TemporaryVenue() {
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("lob.data-directory", () -> {
            try {
                return Files.createTempDirectory("lob-api").toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        registry.add("lob.fsync", () -> "OS");
    }
}
