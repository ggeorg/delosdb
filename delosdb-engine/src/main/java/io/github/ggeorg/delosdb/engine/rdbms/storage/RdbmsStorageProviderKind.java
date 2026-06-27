package io.github.ggeorg.delosdb.engine.rdbms.storage;

/**
 * Storage-provider categories used by DelosDB traces and documentation.
 */
public enum RdbmsStorageProviderKind {
    DERBY_HEAP,
    DERBY_BTREE,
    DELOS_MVCC,
    STORELESS,
    UNKNOWN
}
