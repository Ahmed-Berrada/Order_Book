package io.github.ahmedberrada.lob.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.security.SecureRandom;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Enforces the package rules of {@code package-info.java} and ADR-0001 on the compiled core. */
class ArchitectureTest {

    private static final String CORE = "io.github.ahmedberrada.lob.core";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(CORE);

    @Test
    void dependsOnlyOnTheJdk() {
        classes().should().onlyDependOnClassesThat().resideInAnyPackage("java..", CORE + "..")
                .check(CLASSES);
    }

    @Test
    void neverReadsTheClock() {
        noClasses().should().callMethod(System.class, "currentTimeMillis")
                .orShould().callMethod(System.class, "nanoTime")
                .orShould().dependOnClassesThat().resideInAPackage("java.time..")
                .check(CLASSES);
    }

    @Test
    void neverUsesRandomness() {
        noClasses().should().callMethod(Math.class, "random")
                .orShould().dependOnClassesThat().belongToAnyOf(Random.class, SecureRandom.class, UUID.class)
                .orShould().dependOnClassesThat().resideInAPackage("java.util.random..")
                .check(CLASSES);
    }

    @Test
    void ownsNoThreadsAndNoIo() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "java.util.concurrent..", "java.io..", "java.nio..", "java.net..")
                .orShould().dependOnClassesThat().belongToAnyOf(Thread.class)
                .check(CLASSES);
    }

    @Test
    void neverUsesFloatingPoint() {
        noFields().should().haveRawType(double.class).orShould().haveRawType(float.class)
                .check(CLASSES);
        noMethods().should().haveRawReturnType(double.class).orShould().haveRawReturnType(float.class)
                .check(CLASSES);
    }
}
