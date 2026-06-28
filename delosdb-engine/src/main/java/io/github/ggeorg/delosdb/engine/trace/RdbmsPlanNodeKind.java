package io.github.ggeorg.delosdb.engine.trace;

/**
 * Teachable logical/physical plan node categories.
 */
public enum RdbmsPlanNodeKind {
    TABLE_SCAN,
    INDEX_SCAN,
    FILTER,
    PROJECT,
    JOIN,
    SORT,
    AGGREGATE,
    VALUES,
    INSERT,
    UPDATE,
    DELETE,
    UNKNOWN
}
