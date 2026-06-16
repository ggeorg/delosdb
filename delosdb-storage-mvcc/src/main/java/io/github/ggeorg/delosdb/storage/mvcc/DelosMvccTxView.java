package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;

/** MVCC snapshot adapter for the versioned-storage SPI. */
public final class DelosMvccTxView implements TxView {
    private final MvccSnapshot snapshot;
    private final MvccTransactionCatalog catalog;
    private final MvccCommitSequence oldestVisibleThrough;

    public DelosMvccTxView(
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommitSequence oldestVisibleThrough) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.oldestVisibleThrough = Objects.requireNonNull(oldestVisibleThrough, "oldestVisibleThrough");
    }

    public MvccSnapshot snapshot() {
        return snapshot;
    }

    public MvccTransactionCatalog catalog() {
        return catalog;
    }

    @Override
    public boolean isVisible(long createdByTransactionId, long deletedByTransactionId) {
        MvccTransactionId createdBy = new MvccTransactionId(createdByTransactionId);
        if (!snapshot.isTransactionVisible(createdBy, catalog)) {
            return false;
        }
        if (deletedByTransactionId <= 0L) {
            return true;
        }
        return !snapshot.isTransactionVisible(new MvccTransactionId(deletedByTransactionId), catalog);
    }

    @Override
    public long oldestVisibleTransaction() {
        return oldestVisibleThrough.value();
    }
}
