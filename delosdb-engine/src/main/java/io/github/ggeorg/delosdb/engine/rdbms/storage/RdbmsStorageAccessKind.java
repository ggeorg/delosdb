package io.github.ggeorg.delosdb.engine.rdbms.storage;

/**
 * Storage access categories visible at the engine/storage boundary.
 */
public enum RdbmsStorageAccessKind {
    HEAP_SCAN,
    BTREE_INDEX_SCAN,
    MVCC_SCAN,
    INSERT,
    UPDATE,
    DELETE,
    PREDICATE_PUSHDOWN,
    LEFTOVER_PREDICATE,
    UNKNOWN
}
