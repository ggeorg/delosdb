package io.github.ggeorg.delosdb.spi.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks inherited Derby internals that are preserved for compatibility or
 * staged modernization but are not DelosDB extension contracts.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE})
public @interface LegacyInternal {
    /**
     * Optional short note identifying the compatibility reason or migration path.
     */
    String value() default "";
}
