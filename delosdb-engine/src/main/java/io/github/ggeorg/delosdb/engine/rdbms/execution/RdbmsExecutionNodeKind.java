package io.github.ggeorg.delosdb.engine.rdbms.execution;

/**
 * Coarse executor node categories used in education and trace output.
 */
public enum RdbmsExecutionNodeKind {
    TABLE_SCAN,
    INDEX_SCAN,
    PROJECT_RESTRICT,
    JOIN,
    SORT,
    AGGREGATE,
    INSERT,
    UPDATE,
    DELETE,
    CONSTANT_ROWS,
    UNKNOWN
}
