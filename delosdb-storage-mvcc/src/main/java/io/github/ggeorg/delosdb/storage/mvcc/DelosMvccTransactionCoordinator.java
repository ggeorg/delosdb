package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

/**
 * Small provider-local transaction coordinator for the experimental MVCC
 * module.
 *
 * <p>The DelosDB engine will eventually map Derby transactions to the SPI
 * {@code TxContext}. Until then this class gives provider tests and prototype
 * callers one disciplined way to create, commit, abort, and view MVCC
 * transactions without reaching directly into the lower-level transaction
 * table.</p>
 */
public final class DelosMvccTransactionCoordinator {
    private final MvccTransactionManager transactionManager = new MvccTransactionManager();

    public DelosMvccTxContext begin() {
        MvccTransaction transaction = transactionManager.begin();
        return contextFor(transaction);
    }

    public MvccCommitSequence commit(DelosMvccTxContext context) {
        return transactionManager.commit(requireContext(context).transaction());
    }

    public void abort(DelosMvccTxContext context) {
        transactionManager.abort(requireContext(context).transaction());
    }

    public DelosMvccTxView view(DelosMvccTxContext context) {
        return requireContext(context).mvccView();
    }

    public MvccCleanupResult cleanup(DelosMvccTable<?, ?> table) {
        Objects.requireNonNull(table, "table");
        return table.cleanup(transactionManager);
    }

    MvccTransactionManager transactionManager() {
        return transactionManager;
    }

    private DelosMvccTxContext contextFor(MvccTransaction transaction) {
        return new DelosMvccTxContext(
                transaction,
                transactionManager.snapshot(transaction),
                transactionManager,
                transactionManager.oldestActiveVisibleThrough());
    }

    private static DelosMvccTxContext requireContext(DelosMvccTxContext context) {
        return Objects.requireNonNull(context, "context");
    }
}
