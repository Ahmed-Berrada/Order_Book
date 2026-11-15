package io.github.ahmedberrada.lob.fix;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import quickfix.DoubleField;

class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.github.ahmedberrada.lob.fix");

    /**
     * QuickFIX/J's typed price and quantity fields ({@code Price}, {@code OrderQty}, {@code AvgPx}…) hold
     * {@code double}s. Prices must stay exact, so the gateway reads and writes them by tag as
     * {@code BigDecimal}. Field tag constants are inlined by the compiler and are not dependencies.
     */
    @Test
    void neverUsesDoubleTypedFixFields() {
        noClasses().should().dependOnClassesThat().areAssignableTo(DoubleField.class).check(CLASSES);
    }
}
