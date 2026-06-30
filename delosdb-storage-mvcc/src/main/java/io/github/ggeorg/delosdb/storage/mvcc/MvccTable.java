package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexKeyExtractor;

/**
 * In-memory logical table used to prove MVCC semantics before any Derby heap,
 * B-tree, or WAL integration. Keys are logical row ids, values are immutable row
 * payloads chosen by tests or future prototypes.
 */
public final class MvccTable<K, V> {
    private final Map<K, MvccVersionChain<V>> rows = new LinkedHashMap<>();
    private final Map<K, List<MvccPrunedVersionMarker>> prunedHistoryByKey = new LinkedHashMap<>();
    private final ReentrantReadWriteLock tableLock = new ReentrantReadWriteLock();

    public void insert(K key, V value, MvccTransaction transaction) {
        insert(key, value, transaction, MvccCommandSequence.FIRST);
    }

    public void insert(
            K key,
            V value,
            MvccTransaction transaction,
            MvccCommandSequence commandSequence) {
        writeLocked(() -> insertUnlocked(key, value, transaction, commandSequence));
    }

    public void insert(K key, V value, MvccStatementSnapshot statement) {
        insert(key, value, statement.transaction(), statement.commandSequence());
    }

    public Optional<V> read(K key, MvccStatementSnapshot statement, MvccTransactionCatalog catalog) {
        return read(key, statement.snapshot(), catalog);
    }

    public Optional<V> read(K key, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        return readLocked(() -> {
            requireSnapshotAndCatalog(snapshot, catalog);
            MvccVersionChain<V> chain = rows.get(key);
            if (chain == null) {
                throwIfPrunedHistoryWouldHaveBeenVisible(key, snapshot, catalog);
                return Optional.empty();
            }
            Optional<V> visible = chain.visibleValue(snapshot, catalog);
            if (visible.isEmpty()) {
                throwIfPrunedHistoryWouldHaveBeenVisible(key, snapshot, catalog);
            }
            return visible;
        });
    }

    public MvccScan<K, V> openScan(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        return readLocked(() -> {
            requireSnapshotAndCatalog(snapshot, catalog);
            List<MvccRow<K, V>> visibleRows = new ArrayList<>();
            for (Map.Entry<K, MvccVersionChain<V>> entry : rows.entrySet()) {
                entry.getValue().visibleValue(snapshot, catalog)
                        .ifPresent(value -> visibleRows.add(new MvccRow<>(entry.getKey(), value)));
            }
            throwIfAnyPrunedHistoryWouldHaveBeenVisible(snapshot, catalog);
            return MvccScan.fromVisibleRows(visibleRows);
        });
    }

    public int visibleRowCount(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        return readLocked(() -> {
            try (MvccScan<K, V> scan = openScan(snapshot, catalog)) {
                return scan.visibleRowCount();
            }
        });
    }

    public void update(K key, V value, MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        update(key, value, transaction, snapshot, catalog, MvccCommandSequence.FIRST);
    }

    public void update(
            K key,
            V value,
            MvccTransaction transaction,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommandSequence commandSequence) {
        writeLocked(() -> chainForExistingKey(key).update(value, transaction, snapshot, catalog, commandSequence));
    }

    public void update(
            K key,
            V value,
            MvccStatementSnapshot statement,
            MvccTransactionCatalog catalog) {
        update(key, value, statement.transaction(), statement.snapshot(), catalog, statement.commandSequence());
    }

    public void delete(K key, MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        delete(key, transaction, snapshot, catalog, MvccCommandSequence.FIRST);
    }

    public void delete(
            K key,
            MvccTransaction transaction,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog,
            MvccCommandSequence commandSequence) {
        writeLocked(() -> chainForExistingKey(key).delete(transaction, snapshot, catalog, commandSequence));
    }

    public void delete(
            K key,
            MvccStatementSnapshot statement,
            MvccTransactionCatalog catalog) {
        delete(key, statement.transaction(), statement.snapshot(), catalog, statement.commandSequence());
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
            Iterator<Map.Entry<K, MvccVersionChain<V>>> iterator = rows.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<K, MvccVersionChain<V>> entry = iterator.next();
                MvccVersionChain<V> chain = entry.getValue();
                chain.rollbackTransactionChangesAfter(transaction, savepointBoundary);
                if (chain.isEmpty()) {
                    iterator.remove();
                }
            }
        });
    }

    public int physicalVersionCount(K key) {
        return readLocked(() -> {
            MvccVersionChain<V> chain = rows.get(key);
            return chain == null ? 0 : chain.versionCount();
        });
    }

    public int logicalRowCount() {
        return readLocked(rows::size);
    }

    public int physicalVersionCount() {
        return readLocked(() -> {
            int total = 0;
            for (MvccVersionChain<V> chain : rows.values()) {
                total += chain.versionCount();
            }
            return total;
        });
    }

    public int deadVersionEstimate(MvccCommitSequence oldestVisibleThrough, MvccTransactionCatalog catalog) {
        return readLocked(() -> {
            int total = 0;
            for (MvccVersionChain<V> chain : rows.values()) {
                total += chain.deadVersionEstimate(oldestVisibleThrough, catalog);
            }
            return total;
        });
    }

    public boolean mayHaveVisibleIndexedValue(
            K key,
            Object indexKey,
            VersionedIndexKeyExtractor<V> extractor,
            MvccCommitSequence oldestVisibleThrough,
            MvccTransactionCatalog catalog) {
        return readLocked(() -> {
            MvccVersionChain<V> chain = rows.get(key);
            return chain != null && chain.mayHaveVisibleIndexedValue(indexKey, extractor, oldestVisibleThrough, catalog);
        });
    }

    public MvccCleanupResult cleanup(MvccTransactionManager transactionManager) {
        return writeLocked(() -> {
            MvccCommitSequence oldestVisibleThrough = transactionManager.oldestRetainedVisibleThrough();
            MvccCleanupResult result = new MvccCleanupResult(0);
            Iterator<Map.Entry<K, MvccVersionChain<V>>> iterator = rows.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<K, MvccVersionChain<V>> entry = iterator.next();
                MvccVersionChain<V> chain = entry.getValue();
                result = result.plus(chain.cleanup(oldestVisibleThrough, transactionManager));
                if (chain.isEmpty()) {
                    rememberPrunedHistory(entry.getKey(), chain.prunedHistoryMarkers());
                    iterator.remove();
                    result = result.plus(new MvccCleanupResult(0, 0, 1));
                }
            }
            return result;
        });
    }

    private void insertUnlocked(
            K key,
            V value,
            MvccTransaction transaction,
            MvccCommandSequence commandSequence) {
        if (rows.containsKey(key)) {
            throw new MvccWriteConflictException("logical row already exists: " + key);
        }
        rows.put(key, new MvccVersionChain<>(value, transaction, commandSequence));
    }

    private static void requireSnapshotAndCatalog(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (catalog == null) {
            throw new IllegalArgumentException("catalog must not be null");
        }
    }

    private MvccVersionChain<V> chainForExistingKey(K key) {
        MvccVersionChain<V> chain = rows.get(key);
        if (chain == null) {
            throw new MvccWriteConflictException("logical row does not exist: " + key);
        }
        return chain;
    }

    private void rememberPrunedHistory(K key, List<MvccPrunedVersionMarker> markers) {
        if (markers.isEmpty()) {
            return;
        }
        prunedHistoryByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(markers);
    }

    private void throwIfAnyPrunedHistoryWouldHaveBeenVisible(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        for (Map.Entry<K, List<MvccPrunedVersionMarker>> entry : prunedHistoryByKey.entrySet()) {
            throwIfPrunedHistoryWouldHaveBeenVisible(entry.getKey(), entry.getValue(), snapshot, catalog);
        }
    }

    private void throwIfPrunedHistoryWouldHaveBeenVisible(K key, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        throwIfPrunedHistoryWouldHaveBeenVisible(key, prunedHistoryByKey.getOrDefault(key, List.of()), snapshot, catalog);
    }

    private void throwIfPrunedHistoryWouldHaveBeenVisible(
            K key,
            List<MvccPrunedVersionMarker> markers,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog) {
        for (MvccPrunedVersionMarker marker : markers) {
            if (marker.wouldHaveBeenVisible(snapshot, catalog)) {
                throw new MvccHistoryPrunedException("MVCC history for logical row " + key
                        + " needed by " + snapshot + " was already pruned (" + marker.describe() + ")");
            }
        }
    }

    private <T> T readLocked(Supplier<T> action) {
        tableLock.readLock().lock();
        try {
            return action.get();
        } finally {
            tableLock.readLock().unlock();
        }
    }

    private void writeLocked(Runnable action) {
        tableLock.writeLock().lock();
        try {
            action.run();
        } finally {
            tableLock.writeLock().unlock();
        }
    }

    private <T> T writeLocked(Supplier<T> action) {
        tableLock.writeLock().lock();
        try {
            return action.get();
        } finally {
            tableLock.writeLock().unlock();
        }
    }
}
