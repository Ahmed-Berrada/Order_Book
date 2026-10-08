package io.github.ahmedberrada.lob.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ahmedberrada.lob.core.Rulebook;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrameReaderTest {

    @TempDir
    Path junitDirectory;

    private Path propertyDirectory;

    @BeforeProperty
    void createDirectory() throws IOException {
        propertyDirectory = Files.createTempDirectory("frames");
    }

    @AfterProperty
    void deleteDirectory() throws IOException {
        try (var files = Files.walk(propertyDirectory)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** Writes frames with payload sizes 1, 2, 3… and returns the file. */
    private static Path writeFrames(Path directory, int count) throws IOException {
        Path file = directory.resolve("frames.bin");
        try (FrameWriter writer = new FrameWriter(file, 0)) {
            for (int i = 1; i <= count; i++) {
                byte[] payload = new byte[i];
                payload[0] = (byte) i;
                writer.append(payload);
            }
            writer.force();
        }
        return file;
    }

    private static List<byte[]> readAll(Path file) throws IOException {
        List<byte[]> payloads = new ArrayList<>();
        try (FrameReader reader = new FrameReader(file)) {
            for (byte[] payload = reader.next(); payload != null; payload = reader.next()) {
                payloads.add(payload);
            }
            assertThat(reader.next()).isNull();
        }
        return payloads;
    }

    /** Size of a file holding the first {@code count} frames of {@link #writeFrames}. */
    private static long sizeOfFrames(int count) {
        long size = 0;
        for (int i = 1; i <= count; i++) {
            size += FrameReader.HEADER_BYTES + i;
        }
        return size;
    }

    @Property(tries = 300)
    @Rulebook("RS-003")
    void cuttingTheFileAnywhereKeepsExactlyTheCompleteFrames(
            @ForAll @IntRange(min = 1, max = 30) int count, @ForAll @IntRange(max = 1_000) int cut) throws IOException {
        Path file = writeFrames(propertyDirectory, count);
        long length = Math.min(cut, Files.size(file));
        Files2.truncate(file, length);

        int complete = 0;
        while (complete < count && sizeOfFrames(complete + 1) <= length) {
            complete++;
        }
        try (FrameReader reader = new FrameReader(file)) {
            for (int i = 1; i <= complete; i++) {
                assertThat(reader.next()).hasSize(i).startsWith((byte) i);
            }
            assertThat(reader.next()).isNull();
            assertThat(reader.validLength()).isEqualTo(sizeOfFrames(complete));
            assertThat(reader.tornBytes()).isEqualTo(length - sizeOfFrames(complete));
        }
    }

    @Test
    @Rulebook("RS-003")
    void damagedLastFrameIsATornTail() throws IOException {
        Path file = writeFrames(junitDirectory, 3);
        Files2.flipByte(file, Files.size(file) - 1);

        assertThat(readAll(file)).hasSize(2);
    }

    @Test
    @Rulebook("RS-003")
    void zeroFilledTailIsATornTail() throws IOException {
        Path file = writeFrames(junitDirectory, 3);
        Files2.append(file, new byte[64]);

        try (FrameReader reader = new FrameReader(file)) {
            for (int i = 0; i < 3; i++) {
                assertThat(reader.next()).isNotNull();
            }
            assertThat(reader.next()).isNull();
            assertThat(reader.tornBytes()).isEqualTo(64);
        }
    }

    @Test
    @Rulebook("RS-004")
    void damagedFrameFollowedByMoreDataIsCorruption() throws IOException {
        Path file = writeFrames(junitDirectory, 3);
        Files2.flipByte(file, FrameReader.HEADER_BYTES);      // payload of frame 1

        assertThatThrownBy(() -> readAll(file))
                .isInstanceOf(JournalCorruptedException.class).hasMessageContaining("checksum");
    }

    @Test
    @Rulebook("RS-004")
    void impossibleFrameLengthsAreCorruption() throws IOException {
        Path negative = junitDirectory.resolve("negative.bin");
        Files.write(negative, ByteBuffer.allocate(16).putInt(-5).putInt(0).array());
        assertThatThrownBy(() -> readAll(negative)).isInstanceOf(JournalCorruptedException.class);

        Path zeroThenData = junitDirectory.resolve("zero.bin");
        Files.write(zeroThenData, ByteBuffer.allocate(16).putInt(0).putInt(0).putInt(7).array());
        assertThatThrownBy(() -> readAll(zeroThenData)).isInstanceOf(JournalCorruptedException.class);

        Path zeroWithChecksum = junitDirectory.resolve("zero-checksum.bin");
        Files.write(zeroWithChecksum, ByteBuffer.allocate(16).putInt(0).putInt(9).array());
        assertThatThrownBy(() -> readAll(zeroWithChecksum)).isInstanceOf(JournalCorruptedException.class);

        Path huge = junitDirectory.resolve("huge.bin");
        int length = FrameReader.MAX_PAYLOAD_BYTES + 1;
        Files.write(huge, ByteBuffer.allocate(FrameReader.HEADER_BYTES + length).putInt(length).array());
        assertThatThrownBy(() -> readAll(huge)).isInstanceOf(JournalCorruptedException.class);
    }

    @Test
    void largestAllowedFrameIsAccepted() throws IOException {
        Path file = junitDirectory.resolve("largest.bin");
        try (FrameWriter writer = new FrameWriter(file, 0)) {
            writer.append(new byte[FrameReader.MAX_PAYLOAD_BYTES]);
        }

        assertThat(readAll(file)).singleElement().satisfies(p -> assertThat(p).hasSize(FrameReader.MAX_PAYLOAD_BYTES));
    }

    @Test
    @Rulebook("RS-004")
    void frameHeaderWithANegativeLengthIsCorruptionEvenAtTheEnd() throws IOException {
        Path file = writeFrames(junitDirectory, 1);
        Files2.append(file, ByteBuffer.allocate(FrameReader.HEADER_BYTES).putInt(-1).putInt(0).array());

        assertThatThrownBy(() -> readAll(file)).isInstanceOf(JournalCorruptedException.class);
    }

    @Test
    @Rulebook("RS-008")
    void writerResumesAfterTheValidDataAndCutsOnlyTheTornTail() throws IOException {
        Path file = writeFrames(junitDirectory, 2);
        byte[] before = Files.readAllBytes(file);
        Files2.append(file, new byte[] {1, 2, 3});

        try (FrameWriter writer = new FrameWriter(file, before.length)) {
            writer.append(new byte[] {42});
        }

        assertThat(Files.readAllBytes(file)).startsWith(before);
        assertThat(readAll(file)).hasSize(3).last().isEqualTo(new byte[] {42});
    }
}
