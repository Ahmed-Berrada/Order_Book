package io.github.ahmedberrada.lob.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The regulatory mapping names only rules that exist, and every row has a status. */
class RegulatoryMappingTest {

    private static final Path COMPLIANCE = Path.of("..", "docs", "compliance");
    private static final Path RULEBOOK = Path.of("..", "docs", "rulebook");
    private static final Pattern ID = Pattern.compile("\\b([A-Z]{2,4}-\\d{3})\\b");

    @Test
    void everyRuleNamedInTheMappingExists() throws IOException {
        Set<String> defined = new TreeSet<>();
        try (Stream<Path> files = Files.list(RULEBOOK)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                ID.matcher(Files.readString(file)).results().forEach(m -> defined.add(m.group(1)));
            }
        }
        Set<String> named = new TreeSet<>();
        ID.matcher(Files.readString(COMPLIANCE.resolve("regulatory-mapping.md"))).results()
                .forEach(m -> named.add(m.group(1)));

        assertThat(named).isNotEmpty();
        assertThat(defined).containsAll(named);
    }

    @Test
    void everyRowHasAStatusAndTheDisclaimerIsKept() throws IOException {
        String text = Files.readString(COMPLIANCE.resolve("regulatory-mapping.md"));

        assertThat(text).contains("not legal advice and not a certification");
        text.lines().filter(l -> l.startsWith("| ") && !l.startsWith("| Provision") && !l.startsWith("| Rule ")
                && !l.startsWith("|---")).forEach(row ->
                assertThat(row).matches(".*\\| (Implemented|Partial|Not implemented)\\b.*"));
    }
}
