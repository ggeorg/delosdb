package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;

/** Transaction context adapter for the experimental Delos MVCC provider. */
public final class DelosMvccTxContext implements TxContext {
    private final MvccTransaction transaction;
    private final MvccSnapshot snapshot;
    private final MvccTransactionCatalog catalog;
    private final DelosMvccTxView view;

    public DelosMvccTxContext(
            MvccTransaction transaction,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommitSequence oldestVisibleThrough) {
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
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

    @Override
    public long transactionId() {
        return transaction.id().value();
    }

    @Override
    public TxView currentView() {
        return view;
    }

    public DelosMvccTxView mvccView() {
        return view;
    }
}
