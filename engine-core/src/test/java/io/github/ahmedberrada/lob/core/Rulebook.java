package io.github.ahmedberrada.lob.core;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Links a test to the rulebook rules it verifies, e.g. {@code @Rulebook("CT-002")}.
 * {@link RulebookTraceabilityTest} fails the build if a rule has no such test.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Rulebook {

    /** Rule IDs from {@code docs/rulebook}. */
    String[] value();
}
