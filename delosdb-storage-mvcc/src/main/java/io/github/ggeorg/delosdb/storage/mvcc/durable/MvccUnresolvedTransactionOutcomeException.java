package io.github.ggeorg.delosdb.storage.mvcc.durable;

import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;

/**
 * Raised when strict durable recovery is asked to materialize a mutation whose
 * creating/deleting transaction has no authoritative durable outcome record.
 */
public final class MvccUnresolvedTransactionOutcomeException extends IllegalStateException {
    public MvccUnresolvedTransactionOutcomeException(MvccTransactionId transactionId) {
        super("No durable MVCC transaction outcome recorded for " + transactionId);
    }
}
