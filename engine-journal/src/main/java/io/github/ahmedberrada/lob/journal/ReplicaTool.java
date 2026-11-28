package io.github.ahmedberrada.lob.journal;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Promotes a standby (RS-016): raises the term of every instrument it holds, so that the old primary, at the lower
 * term, is refused by any standby it reaches. Run it with the standby stopped (its directory locks are free), then
 * start the venue on the same root as the new primary.
 *
 * <p>Usage: {@code lob-replica-promote <root>}.
 */
public final class ReplicaTool {

    private ReplicaTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("usage: lob-replica-promote <root>");
            return ReplayTool.USAGE;
        }
        Path root = Path.of(args[0]);
        try (Stream<Path> children = Files.list(root)) {
            List<Path> instruments = children
                    .filter(p -> Files.isRegularFile(p.resolve(JournaledEngine.COMMANDS_FILE))).sorted().toList();
            if (instruments.isEmpty()) {
                err.println("error: no instrument journal under " + root);
                return ReplayTool.FAILED;
            }
            for (Path directory : instruments) {
                try (DirectoryLock lock = DirectoryLock.acquire(directory)) {
                    long term = Replication.term(directory) + 1;
                    Replication.writeTerm(directory, term);
                    out.println(directory.getFileName() + ": term " + term);
                }
            }
            return ReplayTool.OK;
        } catch (IOException e) {
            err.println("error: " + e.getMessage() + " (stop the standby before promoting it)");
            return ReplayTool.FAILED;
        }
    }
}
