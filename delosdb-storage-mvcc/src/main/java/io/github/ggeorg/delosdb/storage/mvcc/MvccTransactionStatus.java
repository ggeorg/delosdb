package io.github.ggeorg.delosdb.storage.mvcc;

/** Transaction lifecycle states needed by MVCC visibility decisions. */
public enum MvccTransactionStatus {
    ACTIVE,
    COMMITTED,
    ABORTED
}
