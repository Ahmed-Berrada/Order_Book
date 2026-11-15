package io.github.ahmedberrada.lob.journal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** Enforces the package rules of {@code package-info.java}. */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.ahmedberrada.lob.journal");

    @Test
    void onlyTheTimeSourceReadsTheClock() {
        noClasses().that().doNotHaveFullyQualifiedName(TimeSource.class.getName())
                .and().haveNameNotMatching(".*TimeSource\\$.*")
                .should().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(System.class, "nanoTime")
                .orShould().callMethod(java.time.Instant.class, "now")
                .check(CLASSES);
    }

    @Test
    void onlyTheCommandLineToolExits() {
        noClasses().that().doNotHaveFullyQualifiedName(ReplayTool.class.getName())
                .should().callMethod(System.class, "exit", int.class)
                .check(CLASSES);
    }
}
