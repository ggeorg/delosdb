package io.github.ggeorg.delosdb.spi.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a DelosDB extension contract that is intended for external provider
 * implementations once the corresponding SPI area is declared stable.
 *
 * <p>Public SPI is stronger than public API: DelosDB must preserve enough
 * behavioral compatibility for third-party providers, not only callers.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE})
public @interface PublicSpi {
}
