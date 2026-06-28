package io.github.ggeorg.delosdb.engine.trace;

/**
 * Storage access categories visible at the engine/storage boundary.
 */
public enum RdbmsStorageAccessKind {
    HEAP_SCAN,
    BTREE_INDEX_SCAN,
    BTREE_KEYED_LOOKUP,
    MVCC_SCAN,
    INSERT,
    UPDATE,
    DELETE,
    PREDICATE_PUSHDOWN,
    LEFTOVER_PREDICATE,
    UNKNOWN
}
