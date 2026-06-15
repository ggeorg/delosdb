package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Newest-first version chain for one logical row. The chain is intentionally
 * small and deterministic; it models MVCC semantics without Derby page or WAL
 * integration.
 */
public final class MvccVersionChain<V> {
    private final List<MvccVersion<V>> newestFirst = new ArrayList<>();

    public MvccVersionChain(V initialValue, MvccTransaction creatingTransaction) {
        newestFirst.add(new MvccVersion<>(initialValue, creatingTransaction.id()));
    }

    public synchronized Optional<V> visibleValue(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        for (MvccVersion<V> version : newestFirst) {
            if (MvccVisibility.isVisible(version, snapshot, catalog)) {
                return Optional.ofNullable(version.value());
            }
        }
        return Optional.empty();
    }

    public synchronized void update(V newValue, MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        MvccVersion<V> current = visibleVersion(snapshot, catalog)
                .orElseThrow(() -> new MvccWriteConflictException("cannot update a row that is not visible to " + transaction.id()));
        current.markDeletedBy(transaction.id(), catalog);
        newestFirst.add(0, new MvccVersion<>(newValue, transaction.id()));
    }

    public synchronized void delete(MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        MvccVersion<V> current = visibleVersion(snapshot, catalog)
                .orElseThrow(() -> new MvccWriteConflictException("cannot delete a row that is not visible to " + transaction.id()));
        current.markDeletedBy(transaction.id(), catalog);
    }

    public synchronized int versionCount() {
        return newestFirst.size();
    }

    public synchronized MvccCleanupResult cleanup(MvccCommitSequence oldestVisibleThrough, MvccTransactionCatalog catalog) {
        int removed = 0;
        Iterator<MvccVersion<V>> iterator = newestFirst.iterator();
        while (iterator.hasNext()) {
            MvccVersion<V> version = iterator.next();
            if (MvccVisibility.isSafeToPrune(version, oldestVisibleThrough, catalog)) {
                iterator.remove();
                removed++;
            }
        }
        return new MvccCleanupResult(removed);
    }

    public synchronized boolean isEmpty() {
        return newestFirst.isEmpty();
    }

    private Optional<MvccVersion<V>> visibleVersion(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        for (MvccVersion<V> version : newestFirst) {
            if (MvccVisibility.isVisible(version, snapshot, catalog)) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
