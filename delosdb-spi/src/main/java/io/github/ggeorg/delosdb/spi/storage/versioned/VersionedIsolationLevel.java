package io.github.ggeorg.delosdb.spi.storage.versioned;

import java.util.Locale;
import java.util.Objects;

/**
 * Explicit isolation policy for provider-owned versioned storage contexts.
 *
 * <p>The enum deliberately names only the MVCC policies the versioned-storage
 * SPI can currently express. It does not change Derby heap isolation levels or
 * default Derby optimizer/store behavior.</p>
 */
public enum VersionedIsolationLevel {
    /** A transaction observes a fresh committed high-water mark for each statement. */
    READ_COMMITTED("read-committed", true),

    /** A transaction reuses its original transaction snapshot across statements. */
    REPEATABLE_READ("repeatable-read", false);

    public static final VersionedIsolationLevel DEFAULT = READ_COMMITTED;

    private final String propertyValue;
    private final boolean refreshesEachStatement;

    VersionedIsolationLevel(String propertyValue, boolean refreshesEachStatement) {
        this.propertyValue = propertyValue;
        this.refreshesEachStatement = refreshesEachStatement;
    }

    public String propertyValue() {
        return propertyValue;
    }

    public boolean refreshesEachStatement() {
        return refreshesEachStatement;
    }

    public static VersionedIsolationLevel fromPropertyValue(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String normalized = value.trim()
                .replace('_', '-')
                .replace(' ', '-')
                .toLowerCase(Locale.ROOT);
        for (VersionedIsolationLevel level : values()) {
            if (level.propertyValue.equals(normalized) || level.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unsupported versioned-storage isolation level: " + value);
    }

    public TxContext statementContext(VersionedTransactionCoordinator coordinator, TxContext transaction) {
        Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(transaction, "transaction");
        return refreshesEachStatement ? coordinator.refresh(transaction) : transaction;
    }
}
