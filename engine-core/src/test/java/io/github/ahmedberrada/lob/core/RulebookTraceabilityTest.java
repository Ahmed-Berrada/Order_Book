package io.github.ahmedberrada.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every rule of the rulebook is verified by at least one test, and tests only cite real rules. */
class RulebookTraceabilityTest {

    private static final Path RULEBOOK = Path.of("..", "docs", "rulebook");
    /** Rules can be verified in any module, so the test sources of every module are searched. */
    private static final Path MODULES = Path.of("..");

    private static final Pattern RULE_DEFINITION = Pattern.compile("\\*\\*([A-Z]{2,4}-\\d{3}):");
    private static final Pattern ANNOTATION = Pattern.compile("@Rulebook\\(([^)]*)\\)");
    private static final Pattern RULE_ID = Pattern.compile("\"([A-Z]{2,4}-\\d{3})\"");

    @Test
    void everyRuleIsVerifiedByATest() throws IOException {
        Set<String> defined = collect(RULEBOOK, ".md", RULE_DEFINITION, null);
        Set<String> cited = new TreeSet<>();
        try (Stream<Path> modules = Files.list(MODULES)) {
            for (Path testSources : modules.map(m -> m.resolve("src/test/java")).filter(Files::isDirectory).toList()) {
                cited.addAll(collect(testSources, ".java", ANNOTATION, RULE_ID));
            }
        }

        assertThat(defined).as("rules defined in %s", RULEBOOK).isNotEmpty();
        assertThat(cited).as("rules cited by tests").containsAll(defined);
        assertThat(defined).as("rules defined in the rulebook").containsAll(cited);
    }

    /** Collects group 1 of {@code outer}, or of {@code inner} applied to it when not null. */
    private static Set<String> collect(Path root, String extension, Pattern outer, Pattern inner)
            throws IOException {
        Set<String> ids = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(extension)).toList()) {
                Matcher matcher = outer.matcher(Files.readString(file));
                while (matcher.find()) {
                    if (inner == null) {
                        ids.add(matcher.group(1));
                    } else {
                        inner.matcher(matcher.group(1)).results().forEach(m -> ids.add(m.group(1)));
                    }
                }
            }
        }
        return ids;
    }
}
