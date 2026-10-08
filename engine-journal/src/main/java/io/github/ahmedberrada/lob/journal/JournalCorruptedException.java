package io.github.ahmedberrada.lob.journal;

import java.io.IOException;

/** A journal or snapshot cannot be read safely (rulebook RS-004). Recovery stops. */
public final class JournalCorruptedException extends IOException {

    public JournalCorruptedException(String message) {
        super(message);
    }
}
