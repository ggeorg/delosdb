package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexKeyExtractor;

/**
 * Newest-first version chain for one logical row. The chain is intentionally
 * small and deterministic; it models MVCC semantics without Derby page or WAL
 * integration.
 */
public final class MvccVersionChain<V> {
    private final List<MvccVersion<V>> newestFirst = new ArrayList<>();
    private final List<MvccPrunedVersionMarker> prunedHistory = new ArrayList<>();

    public MvccVersionChain(V initialValue, MvccTransaction creatingTransaction) {
        this(initialValue, creatingTransaction, MvccCommandSequence.FIRST);
    }

    public MvccVersionChain(
            V initialValue,
            MvccTransaction creatingTransaction,
            MvccCommandSequence createdAtCommand) {
        newestFirst.add(new MvccVersion<>(initialValue, creatingTransaction.id(), createdAtCommand));
    }

    public synchronized Optional<V> visibleValue(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        for (MvccVersion<V> version : newestFirst) {
            if (MvccVisibility.isVisible(version, snapshot, catalog)) {
                return Optional.ofNullable(version.value());
            }
        }
        throwIfPrunedHistoryWouldHaveBeenVisible(snapshot, catalog);
        return Optional.empty();
    }

    public synchronized void update(V newValue, MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        update(newValue, transaction, snapshot, catalog, MvccCommandSequence.FIRST);
    }

    public synchronized void update(
            V newValue,
            MvccTransaction transaction,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommandSequence commandSequence) {
        MvccVersion<V> current = visibleVersion(snapshot, catalog)
                .orElseThrow(() -> new MvccWriteConflictException("cannot update a row that is not visible to " + transaction.id()));
        // markDeletedBy() is deliberately check-before-mutate. If it throws, the
        // old version remains unchanged and no replacement version is appended.
        current.markDeletedBy(transaction.id(), catalog, commandSequence);
        newestFirst.add(0, new MvccVersion<>(newValue, transaction.id(), commandSequence));
    }

    public synchronized void delete(MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        delete(transaction, snapshot, catalog, MvccCommandSequence.FIRST);
    }

    public synchronized void delete(
            MvccTransaction transaction,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommandSequence commandSequence) {
        MvccVersion<V> current = visibleVersion(snapshot, catalog)
                .orElseThrow(() -> new MvccWriteConflictException("cannot delete a row that is not visible to " + transaction.id()));
        current.markDeletedBy(transaction.id(), catalog, commandSequence);
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
                MvccPrunedVersionMarker marker = version.prunedVersionMarker(catalog);
                if (marker != null) {
                    prunedHistory.add(marker);
                }
                iterator.remove();
                removed++;
            }
        }
        return new MvccCleanupResult(removed);
    }

    public synchronized int deadVersionEstimate(MvccCommitSequence oldestVisibleThrough, MvccTransactionCatalog catalog) {
        int estimate = 0;
        for (MvccVersion<V> version : newestFirst) {
            if (MvccVisibility.isSafeToPrune(version, oldestVisibleThrough, catalog)) {
                estimate++;
            }
        }
        return estimate;
    }

    public synchronized boolean mayHaveVisibleIndexedValue(
            Object indexKey,
            VersionedIndexKeyExtractor<V> extractor,
            MvccCommitSequence oldestVisibleThrough,
            MvccTransactionCatalog catalog) {
        Objects.requireNonNull(extractor, "extractor");
        for (MvccVersion<V> version : newestFirst) {
            if (!MvccVisibility.isSafeToPrune(version, oldestVisibleThrough, catalog)
                    && Objects.equals(indexKey, extractor.extract(version.value()))) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isEmpty() {
        return newestFirst.isEmpty();
    }

    synchronized List<MvccPrunedVersionMarker> prunedHistoryMarkers() {
        return List.copyOf(prunedHistory);
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
}
