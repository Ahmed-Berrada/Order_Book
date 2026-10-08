package io.github.ahmedberrada.lob.journal;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** File surgery used to simulate crashes and disk damage. */
final class Files2 {

    private Files2() {
    }

    static void truncate(Path file, long length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(length);
        }
    }

    static void flipByte(Path file, long offset) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(offset);
            int value = raf.read();
            raf.seek(offset);
            raf.write(value ^ 0xFF);
        }
    }

    static void append(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes, StandardOpenOption.APPEND);
    }
}
