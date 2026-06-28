package io.github.ggeorg.delosdb.engine.trace;

/**
 * Modern transaction and recovery concepts that DelosDB should make observable over time.
 */
public enum RdbmsTransactionConcept {
    TRANSACTION,
    SNAPSHOT,
    VISIBILITY_CHECK,
    ISOLATION_LEVEL,
    WAL_POSITION,
    CHECKPOINT,
    VACUUM_HORIZON,
    RECOVERY_REDO,
    UNKNOWN
}
