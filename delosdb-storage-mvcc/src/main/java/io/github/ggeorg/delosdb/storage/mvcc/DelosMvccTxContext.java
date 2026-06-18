package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;

/** Transaction context adapter for the experimental Delos MVCC provider. */
public final class DelosMvccTxContext implements TxContext {
    private final MvccTransaction transaction;
    private final MvccSnapshot snapshot;
    private final MvccTransactionCatalog catalog;
    private final MvccCommandSequence commandSequence;
    private final DelosMvccTxView view;

    public DelosMvccTxContext(
            MvccTransaction transaction,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommitSequence oldestVisibleThrough) {
        this(transaction, snapshot, catalog, oldestVisibleThrough, MvccCommandSequence.FIRST);
    }

    public DelosMvccTxContext(
            MvccTransaction transaction,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommitSequence oldestVisibleThrough,
            MvccCommandSequence commandSequence) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.commandSequence = Objects.requireNonNull(commandSequence, "commandSequence");
        this.view = new DelosMvccTxView(snapshot, catalog, oldestVisibleThrough);
    }

    public MvccTransaction transaction() {
        return transaction;
    }

    public MvccSnapshot snapshot() {
        return snapshot;
    }

    public MvccTransactionCatalog catalog() {
        return catalog;
    }

    public MvccCommandSequence commandSequence() {
        return commandSequence;
    }

    @Override
    public long transactionId() {
        return transaction.id().value();
    }

    @Override
    public TxView currentView() {
        return view;
    }

    @Override
    public long statementCommandSequence() {
        return commandSequence.value();
    }

    public DelosMvccTxView mvccView() {
        return view;
    }
}
