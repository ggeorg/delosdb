package io.github.ggeorg.delosdb.storage.mvcc.api;

/** Durable transaction outcome states visible to MVCC storage adapters. */
public enum MvccTransactionStatus {
    ACTIVE,
    COMMITTED,
    ABORTED,
    RECOVERY_PENDING
}
