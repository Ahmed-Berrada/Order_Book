package io.github.ahmedberrada.lob.journal;

import io.github.ahmedberrada.lob.core.EngineSnapshot;
import io.github.ahmedberrada.lob.core.Instrument;
import io.github.ahmedberrada.lob.core.MatchingEngine;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Snapshot files of one instrument: {@code snapshot-<command sequence>.snap}, each holding a single
 * frame. A snapshot is written to a temporary file, flushed, then atomically renamed, so a crash leaves
 * either the complete new file or none of it.
 */
final class SnapshotStore {

    static final int RETAINED = 2;
    private static final String PREFIX = "snapshot-";
    private static final String SUFFIX = ".snap";
    private static final String TEMPORARY = ".tmp";

    private final Path directory;
    private final Instrument instrument;

    SnapshotStore(Path directory, Instrument instrument) {
        this.directory = directory;
        this.instrument = instrument;
    }

    void write(long lastCommandSequence, EngineSnapshot state) throws IOException {
        Path target = directory.resolve(PREFIX + String.format("%020d", lastCommandSequence) + SUFFIX);
        Path temporary = directory.resolve(target.getFileName() + TEMPORARY);
        byte[] payload = Codec.encodeSnapshot(new Codec.StoredSnapshot(lastCommandSequence, state));
        try (FrameWriter writer = new FrameWriter(temporary, 0)) {
            writer.append(payload);
            writer.force();
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory();
        List<Path> snapshots = newestFirst();
        for (Path old : snapshots.subList(Math.min(RETAINED, snapshots.size()), snapshots.size())) {
            Files.deleteIfExists(old);
        }
    }

    /** Snapshot files, newest first. Leftover temporary files from a crash are deleted. */
    List<Path> newestFirst() throws IOException {
        List<Path> snapshots = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(PREFIX) && name.endsWith(SUFFIX + TEMPORARY)) {
                    Files.deleteIfExists(file);
                } else if (name.startsWith(PREFIX) && name.endsWith(SUFFIX)) {
                    snapshots.add(file);
                }
            }
        }
        snapshots.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return snapshots;
    }

    /**
     * Loads a snapshot, or returns empty if it cannot be trusted: damaged, written for another
     * instrument, or inconsistent (RS-005). The caller then falls back to an older one.
     */
    Optional<Codec.StoredSnapshot> load(Path file) throws IOException {
        try (FrameReader frames = new FrameReader(file)) {
            byte[] payload = frames.next();
            if (payload == null || frames.next() != null || frames.tornBytes() != 0) {
                return Optional.empty();
            }
            Codec.StoredSnapshot snapshot = Codec.decodeSnapshot(payload);
            if (!snapshot.state().instrument().equals(instrument)) {
                return Optional.empty();
            }
            MatchingEngine.restore(snapshot.state());
            return Optional.of(snapshot);
        } catch (JournalCorruptedException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Makes the rename durable. Not supported on every platform, where it is skipped. */
    private void forceDirectory() {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            // Windows cannot open a directory as a channel; the rename is still atomic there.
        }
    }
}
