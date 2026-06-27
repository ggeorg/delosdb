package io.github.ggeorg.delosdb.engine.rdbms.pipeline;

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
    ERROR
}
