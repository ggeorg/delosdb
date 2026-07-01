package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Newest-first version chain for one logical row. The chain is intentionally
 * small and deterministic; it models MVCC semantics without Derby page or WAL
 * integration.
 */
public final class MvccVersionChain<V> {
    private final List<MvccVersion<V>> newestFirst = new ArrayList<>();
    private final List<MvccPrunedVersionMarker> prunedHistory = new ArrayList<>();
    private final ReentrantReadWriteLock chainLock = new ReentrantReadWriteLock();

    public MvccVersionChain(V initialValue, MvccTransaction creatingTransaction) {
        this(initialValue, creatingTransaction, MvccCommandSequence.FIRST);
    }

    public MvccVersionChain(
            V initialValue,
            MvccTransaction creatingTransaction,
            MvccCommandSequence createdAtCommand) {
        newestFirst.add(new MvccVersion<>(initialValue, creatingTransaction.id(), createdAtCommand));
    }

    public Optional<V> visibleValue(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        return readLocked(() -> {
            for (MvccVersion<V> version : newestFirst) {
                if (MvccVisibility.isVisible(version, snapshot, catalog)) {
                    return Optional.ofNullable(version.value());
                }
            }
            throwIfPrunedHistoryWouldHaveBeenVisible(snapshot, catalog);
            return Optional.empty();
        });
    }

    public void update(V newValue, MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        update(newValue, transaction, snapshot, catalog, MvccCommandSequence.FIRST);
    }

    public void update(
            V newValue,
            MvccTransaction transaction,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommandSequence commandSequence) {
        writeLocked(() -> {
            MvccVersion<V> current = visibleVersion(snapshot, catalog)
                    .orElseThrow(() -> new MvccWriteConflictException("cannot update a row that is not visible to " + transaction.id()));
            // markDeletedBy() is deliberately check-before-mutate. If it throws, the
            // old version remains unchanged and no replacement version is appended.
            current.markDeletedBy(transaction.id(), catalog, commandSequence);
            newestFirst.add(0, new MvccVersion<>(newValue, transaction.id(), commandSequence));
        });
    }

    public void delete(MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        delete(transaction, snapshot, catalog, MvccCommandSequence.FIRST);
    }

    public void delete(
            MvccTransaction transaction,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommandSequence commandSequence) {
        writeLocked(() -> {
            MvccVersion<V> current = visibleVersion(snapshot, catalog)
                    .orElseThrow(() -> new MvccWriteConflictException("cannot delete a row that is not visible to " + transaction.id()));
            current.markDeletedBy(transaction.id(), catalog, commandSequence);
        });
    }

    public void rollbackTransactionChangesAfter(
            MvccTransaction transaction,
            MvccCommandSequence savepointBoundary) {
        writeLocked(() -> {
            if (transaction == null) {
                throw new IllegalArgumentException("transaction must not be null");
            }
            if (savepointBoundary == null) {
                throw new IllegalArgumentException("savepointBoundary must not be null");
            }
            Iterator<MvccVersion<V>> iterator = newestFirst.iterator();
            while (iterator.hasNext()) {
                MvccVersion<V> version = iterator.next();
                if (version.wasCreatedBy(transaction.id()) && version.wasCreatedAfter(savepointBoundary)) {
                    iterator.remove();
                } else {
                    version.clearDeletionByAfter(transaction.id(), savepointBoundary);
                }
            }
        });
    }

    public int versionCount() {
        return readLocked(newestFirst::size);
    }

    public MvccCleanupResult cleanup(MvccCommitSequence oldestVisibleThrough, MvccTransactionCatalog catalog) {
        return writeLocked(() -> {
            int removed = 0;
            Iterator<MvccVersion<V>> iterator = newestFirst.iterator();
            while (iterator.hasNext()) {
                MvccVersion<V> version = iterator.next();
                if (MvccVisibility.isSafeToPrune(version, oldestVisibleThrough, catalog)) {
                    MvccPrunedVersionMarker marker = version.prunedVersionMarker(catalog);
                    if (marker != null) {
                        prunedHistory.add(marker);
                    }
                    iterator.remove();
                    removed++;
                }
            }
            return new MvccCleanupResult(removed);
        });
    }

    public int deadVersionEstimate(MvccCommitSequence oldestVisibleThrough, MvccTransactionCatalog catalog) {
        return readLocked(() -> {
            int estimate = 0;
            for (MvccVersion<V> version : newestFirst) {
                if (MvccVisibility.isSafeToPrune(version, oldestVisibleThrough, catalog)) {
                    estimate++;
                }
            }
            return estimate;
        });
    }

    public boolean mayHaveVisibleIndexedValue(
            Object indexKey,
            Function<V, Object> extractor,
            MvccCommitSequence oldestVisibleThrough,
            MvccTransactionCatalog catalog) {
        return readLocked(() -> {
            Objects.requireNonNull(extractor, "extractor");
            for (MvccVersion<V> version : newestFirst) {
                if (!MvccVisibility.isSafeToPrune(version, oldestVisibleThrough, catalog)
                        && Objects.equals(indexKey, extractor.apply(version.value()))) {
                    return true;
                }
            }
            return false;
        });
    }

    public boolean isEmpty() {
        return readLocked(newestFirst::isEmpty);
    }

    List<MvccPrunedVersionMarker> prunedHistoryMarkers() {
        return readLocked(() -> List.copyOf(prunedHistory));
    }

    private Optional<MvccVersion<V>> visibleVersion(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        for (MvccVersion<V> version : newestFirst) {
            if (MvccVisibility.isVisible(version, snapshot, catalog)) {
                return Optional.of(version);
            }
        }
        throwIfPrunedHistoryWouldHaveBeenVisible(snapshot, catalog);
        return Optional.empty();
    }

    private void throwIfPrunedHistoryWouldHaveBeenVisible(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        for (MvccPrunedVersionMarker marker : prunedHistory) {
            if (marker.wouldHaveBeenVisible(snapshot, catalog)) {
                throw new MvccHistoryPrunedException("MVCC history needed by " + snapshot
                        + " was already pruned (" + marker.describe() + ")");
            }
        }
    }

    private <T> T readLocked(Supplier<T> action) {
        chainLock.readLock().lock();
        try {
            return action.get();
        } finally {
            chainLock.readLock().unlock();
        }
    }

    private void writeLocked(Runnable action) {
        chainLock.writeLock().lock();
        try {
            action.run();
        } finally {
            chainLock.writeLock().unlock();
        }
    }

    private <T> T writeLocked(Supplier<T> action) {
        chainLock.writeLock().lock();
        try {
            return action.get();
        } finally {
            chainLock.writeLock().unlock();
        }
    }
}
