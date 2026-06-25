package io.github.ggeorg.delosdb.storage.mvcc;

/**
 * Durable transaction outcome vocabulary used by the persistent MVCC status
 * store. The current MODULE5H scope treats unresolved recovered transactions as
 * recovery-pending and therefore invisible; later WAL recovery may resolve them.
 */
public enum MvccTransactionOutcome {
    ACTIVE(MvccTransactionStatus.ACTIVE),
    COMMITTED(MvccTransactionStatus.COMMITTED),
    ABORTED(MvccTransactionStatus.ABORTED),
    RECOVERY_PENDING(MvccTransactionStatus.RECOVERY_PENDING);

    private final MvccTransactionStatus status;

    MvccTransactionOutcome(MvccTransactionStatus status) {
        this.status = status;
    }

    public MvccTransactionStatus status() {
        return status;
    }

    public static MvccTransactionOutcome fromStatus(MvccTransactionStatus status) {
        return switch (status) {
        case ACTIVE -> ACTIVE;
        case COMMITTED -> COMMITTED;
        case ABORTED -> ABORTED;
        case RECOVERY_PENDING -> RECOVERY_PENDING;
        };
    }
}
