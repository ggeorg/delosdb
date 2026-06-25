package io.github.ggeorg.delosdb.storage.mvcc;

/** Transaction lifecycle states needed by MVCC visibility decisions. */
public enum MvccTransactionStatus {
    ACTIVE,
    COMMITTED,
    ABORTED,
    /** Recovered transaction that was not durably committed or aborted yet. */
    RECOVERY_PENDING
}
