package io.github.ahmedberrada.lob.journal;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Promotes a standby (RS-016): raises every instrument it holds, and its root, to one term above any it has seen,
 * so that the old primary, at a lower term, is refused for every instrument by any standby it reaches. Either every
 * instrument is promoted or none is. Run it with the standby stopped (its directory locks are free), then
 * start the venue on the same root as the new primary.
 *
 * <p>Usage: {@code lob-replica-promote <root>}; {@code lob-replica-promote --term <term> <root>} seeds a new standby
 * with the term of the primary it is for.
 */
public final class ReplicaTool {

    private ReplicaTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Starts a new standby at the new primary's term, before it is reachable: until then a deposed primary at a lower
     * term that reaches it first would be accepted (RS-016).
     */
    private static int seed(Path root, long term, PrintStream out, PrintStream err) {
        if (term < 1) {
            err.println("error: terms start at 1");
            return ReplayTool.USAGE;
        }
        try {
            Files.createDirectories(root);
            try (DirectoryLock lock = DirectoryLock.acquire(root)) {
                long seeded = Math.max(term, Replication.term(root));
                Replication.writeTerm(root, seeded);
                out.println(root + ": refuses primaries below term " + seeded);
                return ReplayTool.OK;
            }
        } catch (IOException e) {
            err.println("error: " + e.getMessage() + " (stop the standby first)");
            return ReplayTool.FAILED;
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 3 && args[0].equals("--term") && args[1].matches("\\d{1,18}")) {
            return seed(Path.of(args[2]), Long.parseLong(args[1]), out, err);
        }
        if (args.length != 1) {
            err.println("usage: lob-replica-promote <root> | lob-replica-promote --term <term> <new-standby-root>");
            return ReplayTool.USAGE;
        }
        Path root = Path.of(args[0]);
        List<DirectoryLock> locks = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            List<Path> instruments = children
                    .filter(p -> Files.isRegularFile(p.resolve(JournaledEngine.COMMANDS_FILE))).sorted().toList();
            if (instruments.isEmpty()) {
                err.println("error: no instrument journal under " + root);
                return ReplayTool.FAILED;
            }
            // Every lock first: a standby still serving any instrument is not promoted at all.
            locks.add(DirectoryLock.acquire(root));
            long term = Replication.term(root);
            for (Path directory : instruments) {
                locks.add(DirectoryLock.acquire(directory));
                term = Math.max(term, Replication.term(directory));
            }
            term++;
            for (Path directory : instruments) {
                Replication.writeTerm(directory, term);
                out.println(directory.getFileName() + ": term " + term);
            }
            Replication.writeTerm(root, term);          // last: it fences every other instrument too
            return ReplayTool.OK;
        } catch (IOException e) {
            err.println("error: " + e.getMessage() + " (stop the standby before promoting it)");
            return ReplayTool.FAILED;
        } finally {
            for (DirectoryLock lock : locks) {
                try {
                    lock.close();
                } catch (IOException e) {
                    // released when the process exits
                }
            }
        }
    }
}
