package io.github.ahmedberrada.lob.journal;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The standby did not confirm (RS-015): the commands it lacks are on this primary's device but not acknowledged, and
 * the instance has stopped. Like a failed flush, their outcome is unknown until recovery.
 */
public final class ReplicationException extends UncheckedIOException {

    private static final long serialVersionUID = 1L;

    public ReplicationException(String message, IOException cause) {
        super(message, cause);
    }
}
