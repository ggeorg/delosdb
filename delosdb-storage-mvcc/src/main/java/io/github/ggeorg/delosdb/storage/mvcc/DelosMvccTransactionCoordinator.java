package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/**
 * Small provider-local transaction coordinator for the experimental MVCC
 * module.
 *
 * <p>The DelosDB engine maps JDBC commit/rollback to this coordinator for the
 * experimental SQL table-scan path. The coordinator still remains provider
 * local: it does not depend on Derby heap pages, locks, or log records.</p>
 */
public final class DelosMvccTransactionCoordinator implements VersionedTransactionCoordinator {
    private final MvccTransactionManager transactionManager = new MvccTransactionManager();

    @Override
    public DelosMvccTxContext begin() {
        MvccTransaction transaction = transactionManager.begin();
        return contextFor(transaction);
    }

    public MvccCommitSequence commitMvcc(DelosMvccTxContext context) {
        return transactionManager.commit(requireContext(context).transaction());
    }

    @Override
    public void commit(TxContext context) {
        transactionManager.commit(requireContext(context).transaction());
    }

    public void abort(DelosMvccTxContext context) {
        transactionManager.abort(requireContext(context).transaction());
    }

    @Override
    public void abort(TxContext context) {
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

    private static DelosMvccTxContext requireContext(TxContext context) {
        if (context instanceof DelosMvccTxContext mvccContext) {
            return mvccContext;
        }
        throw new IllegalArgumentException("Delos MVCC coordinator requires DelosMvccTxContext");
    }
}
