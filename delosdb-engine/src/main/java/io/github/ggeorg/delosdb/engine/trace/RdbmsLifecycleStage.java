package io.github.ggeorg.delosdb.engine.trace;

/**
 * Coarse stages in the lifecycle of a SQL statement in a modern relational database system.
 */
public enum RdbmsLifecycleStage {
    SQL_TEXT_RECEIVED,
    PARSED,
    BOUND,
    OPTIMIZED,
    PHYSICAL_PLAN_CREATED,
    EXECUTION_STARTED,
    STORAGE_ACCESSED,
    ROWS_PRODUCED,
    EXECUTION_FINISHED,
    TRANSACTION_COMMITTED,
    TRANSACTION_ROLLED_BACK,
    ERROR
}
