package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/**
 * Small provider-local transaction coordinator for the experimental MVCC
 * module.
 *
 * <p>The DelosDB engine maps JDBC commit/rollback to this coordinator for the
 * experimental SQL table-scan path. The coordinator remains provider-local and
 * can optionally emit provider-owned recovery-log commit/abort records.</p>
 */
public final class DelosMvccTransactionCoordinator implements VersionedTransactionCoordinator {
    private final MvccTransactionManager transactionManager;
    private static final BooleanSupplier NEVER_SUPPRESS_LOGGING = () -> false;
    private static final TransactionCompletionListener NOOP_LISTENER = new TransactionCompletionListener() {
        @Override
        public void committed(long transactionId, MvccCommitSequence commitSequence) {
        }

        @Override
        public void aborted(long transactionId) {
        }
    };

    private final DelosMvccStorageLog storageLog;
    private final BooleanSupplier loggingSuppressed;
    private final TransactionCompletionListener completionListener;

    public DelosMvccTransactionCoordinator() {
        this(DelosMvccStorageLog.disabled(), MvccTransactionStatusStore.disabled(), NEVER_SUPPRESS_LOGGING);
    }

    DelosMvccTransactionCoordinator(DelosMvccStorageLog storageLog, BooleanSupplier loggingSuppressed) {
        this(storageLog, MvccTransactionStatusStore.disabled(), loggingSuppressed, NOOP_LISTENER);
    }

    DelosMvccTransactionCoordinator(
            DelosMvccStorageLog storageLog,
            MvccTransactionStatusStore statusStore,
            BooleanSupplier loggingSuppressed) {
        this(storageLog, statusStore, loggingSuppressed, NOOP_LISTENER);
    }

    DelosMvccTransactionCoordinator(
            DelosMvccStorageLog storageLog,
            MvccTransactionStatusStore statusStore,
            BooleanSupplier loggingSuppressed,
            TransactionCompletionListener completionListener) {
        this.storageLog = Objects.requireNonNull(storageLog, "storageLog");
        this.transactionManager = new MvccTransactionManager(Objects.requireNonNull(statusStore, "statusStore"));
        this.loggingSuppressed = Objects.requireNonNull(loggingSuppressed, "loggingSuppressed");
        this.completionListener = Objects.requireNonNull(completionListener, "completionListener");
    }

    @Override
    public DelosMvccTxContext begin() {
        MvccTransaction transaction = transactionManager.begin();
        return contextFor(transaction);
    }

    public MvccCommitSequence commitMvcc(DelosMvccTxContext context) {
        DelosMvccTxContext mvccContext = requireContext(context);
        MvccCommitSequence sequence = transactionManager.commit(mvccContext.transaction());
        appendCommitIfEnabled(mvccContext.transactionId());
        completionListener.committed(mvccContext.transactionId(), sequence);
        return sequence;
    }

    @Override
    public void commit(TxContext context) {
        DelosMvccTxContext mvccContext = requireContext(context);
        MvccCommitSequence sequence = transactionManager.commit(mvccContext.transaction());
        appendCommitIfEnabled(mvccContext.transactionId());
        completionListener.committed(mvccContext.transactionId(), sequence);
    }

    public void abort(DelosMvccTxContext context) {
        DelosMvccTxContext mvccContext = requireContext(context);
        transactionManager.abort(mvccContext.transaction());
        appendAbortIfEnabled(mvccContext.transactionId());
        completionListener.aborted(mvccContext.transactionId());
    }

    @Override
    public void abort(TxContext context) {
        DelosMvccTxContext mvccContext = requireContext(context);
        transactionManager.abort(mvccContext.transaction());
        appendAbortIfEnabled(mvccContext.transactionId());
        completionListener.aborted(mvccContext.transactionId());
    }

    public DelosMvccTxView view(DelosMvccTxContext context) {
        return requireContext(context).mvccView();
    }

    /**
     * Captures a fresh statement view for the same MVCC transaction.
     *
     * <p>This is the provider-side primitive needed by READ COMMITTED: the
     * transaction id remains stable, so own writes remain visible, but the
     * visible commit high-water mark advances for the next statement.</p>
     */
    @Override
    public DelosMvccTxContext refresh(TxContext context) {
        DelosMvccTxContext mvccContext = requireContext(context);
        MvccStatementSnapshot statement = transactionManager.beginStatement(mvccContext.transaction());
        return new DelosMvccTxContext(
                statement.transaction(),
                statement.snapshot(),
                transactionManager,
                transactionManager.oldestActiveVisibleThrough(),
                statement.commandSequence());
    }

    public MvccCleanupResult cleanup(DelosMvccTable<?, ?> table) {
        Objects.requireNonNull(table, "table");
        return table.cleanup(transactionManager);
    }

    boolean hasActiveTransactions() {
        return transactionManager.activeTransactionCount() > 0;
    }


    interface TransactionCompletionListener {
        void committed(long transactionId, MvccCommitSequence commitSequence);

        void aborted(long transactionId);
    }

    private void appendCommitIfEnabled(long transactionId) {
        if (!loggingSuppressed.getAsBoolean()) {
            storageLog.appendCommit(transactionId);
        }
    }

    private void appendAbortIfEnabled(long transactionId) {
        if (!loggingSuppressed.getAsBoolean()) {
            storageLog.appendAbort(transactionId);
        }
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
