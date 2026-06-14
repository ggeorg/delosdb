package io.github.ggeorg.delosdb.engine.logging;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDK logging bridge for DelosDB implementation code.
 *
 * <p>The engine remains dependency-free at runtime: this facade exposes
 * {@link java.util.logging.Logger} names for new DelosDB code without adding
 * SLF4J, Log4j, or any other external logging dependency. Applications which
 * want to route DelosDB messages to another logging stack can bridge JUL at the
 * application boundary.</p>
 */
@InternalApi
public final class DelosLogger {

    public static final String ROOT_LOGGER_NAME = "io.github.ggeorg.delosdb";
    public static final String ENGINE_LOGGER_NAME = ROOT_LOGGER_NAME + ".engine";
    public static final String EXTENSIONS_LOGGER_NAME = ROOT_LOGGER_NAME + ".extensions";

    private DelosLogger() {
    }

    public static Logger root() {
        return Logger.getLogger(ROOT_LOGGER_NAME);
    }

    public static Logger engine() {
        return Logger.getLogger(ENGINE_LOGGER_NAME);
    }

    public static Logger extensions() {
        return Logger.getLogger(EXTENSIONS_LOGGER_NAME);
    }

    public static Logger provider(String providerType, String providerName) {
        return Logger.getLogger(EXTENSIONS_LOGGER_NAME + '.'
                + loggerSegment(providerType) + '.' + loggerSegment(providerName));
    }

    public static void fine(Logger logger, String message) {
        Objects.requireNonNull(logger, "logger");
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(message);
        }
    }

    public static void warning(Logger logger, String message, Throwable throwable) {
        Objects.requireNonNull(logger, "logger");
        logger.log(Level.WARNING, message, throwable);
    }

    private static String loggerSegment(String value) {
        String trimmed = Objects.requireNonNull(value, "value").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("logger name segment must not be blank");
        }
        return trimmed.replaceAll("[^A-Za-z0-9_.$-]", "_");
    }
}
