package io.github.ahmedberrada.lob.journal;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;

/**
 * Checks an instrument's command journal against its seals with the venue's public key alone (RS-013): what an
 * auditor runs on a copy of the journal. Prints how much of the journal is sealed and up to which command.
 *
 * <p>Usage: {@code lob-verify <instrument-directory> <public-key.pem> [--expect <sequence>] [--strict]}. Seals alone
 * cannot tell an older journal, restored with its older seals, from the current one: {@code --expect} names a
 * command sequence recorded outside the venue (an earlier run of this tool, an audit log), and fails if the latest
 * seal is older. {@code --strict} fails if bytes follow the last seal. Exit status 0: every seal holds; 1: the
 * journal was changed after it was sealed, is older than expected, has an unsealed tail under {@code --strict}, or
 * cannot be read; 2: usage.
 */
public final class JournalVerify {

    private JournalVerify() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        long expected = 0;
        boolean strict = false;
        for (int i = 2; i < args.length; i++) {
            if ("--strict".equals(args[i])) {
                strict = true;
            } else if ("--expect".equals(args[i]) && i + 1 < args.length && args[i + 1].matches("\\d{1,18}")) {
                expected = Long.parseLong(args[++i]);
            } else {
                expected = -1;
                break;
            }
        }
        if (args.length < 2 || expected < 0) {
            err.println("usage: lob-verify <instrument-directory> <public-key.pem> [--expect <sequence>] [--strict]");
            return ReplayTool.USAGE;
        }
        Path directory = Path.of(args[0]);
        Path journal = directory.resolve(JournaledEngine.COMMANDS_FILE);
        Path seals = directory.resolve(JournalSealer.FILE);
        try {
            if (!Files.exists(journal)) {
                err.println("error: no command journal in " + directory);
                return ReplayTool.FAILED;
            }
            PublicKey key = SealKeys.publicKey(Path.of(args[1]));
            String symbol;
            try (FrameReader reader = new FrameReader(journal)) {
                byte[] header = reader.next();
                if (header == null) {
                    err.println("error: " + journal + " has no header");
                    return ReplayTool.FAILED;
                }
                symbol = Codec.decodeHeader(header).instrument().symbol();
            }
            JournalSealer.Verified verified = JournalSealer.verify(journal, seals, symbol, key);
            if (verified.seals().isEmpty()) {
                out.println(symbol + ": no seal; " + verified.journalLength() + " bytes unsealed");
                return verified.journalLength() == 0 ? ReplayTool.OK : ReplayTool.FAILED;
            }
            JournalSealer.Seal last = verified.seals().getLast();
            out.println(symbol + ": " + verified.seals().size() + " seals hold; sealed up to command "
                    + last.lastSequence() + " (" + last.length() + " bytes) at "
                    + java.time.Instant.EPOCH.plus(last.timestampMicros(), java.time.temporal.ChronoUnit.MICROS) + "; "
                    + verified.unsealedBytes() + " bytes after the last seal");
            if (last.lastSequence() < expected) {
                err.println("OLDER: sealed up to command " + last.lastSequence() + ", expected at least " + expected
                        + ": the journal was rolled back or cut at a seal");
                return ReplayTool.FAILED;
            }
            if (strict && verified.unsealedBytes() > 0) {
                err.println("UNSEALED: " + verified.unsealedBytes() + " bytes after the last seal");
                return ReplayTool.FAILED;
            }
            return ReplayTool.OK;
        } catch (JournalCorruptedException e) {
            err.println("TAMPERED: " + e.getMessage());
            return ReplayTool.FAILED;
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return ReplayTool.FAILED;
        }
    }
}
