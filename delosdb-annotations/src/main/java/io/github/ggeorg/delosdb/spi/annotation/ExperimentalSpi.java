package io.github.ggeorg.delosdb.spi.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a DelosDB extension contract that is intentionally available for
 * early provider experiments but may change before it becomes public SPI.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE})
public @interface ExperimentalSpi {
    /**
     * Optional short note describing the experiment or graduation condition.
     */
    String value() default "";
}
