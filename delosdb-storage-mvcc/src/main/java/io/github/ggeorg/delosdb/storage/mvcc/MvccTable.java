package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexKeyExtractor;

/**
 * In-memory logical table used to prove MVCC semantics before any Derby heap,
 * B-tree, or WAL integration. Keys are logical row ids, values are immutable row
 * payloads chosen by tests or future prototypes.
 */
public final class MvccTable<K, V> {
    private final Map<K, MvccVersionChain<V>> rows = new LinkedHashMap<>();
    private final Map<K, List<MvccPrunedVersionMarker>> prunedHistoryByKey = new LinkedHashMap<>();

    public synchronized void insert(K key, V value, MvccTransaction transaction) {
        if (rows.containsKey(key)) {
            throw new MvccWriteConflictException("logical row already exists: " + key);
        }
        rows.put(key, new MvccVersionChain<>(value, transaction));
    }

    public synchronized Optional<V> read(K key, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
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
    }

    public synchronized MvccScan<K, V> openScan(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        requireSnapshotAndCatalog(snapshot, catalog);
        List<MvccRow<K, V>> visibleRows = new ArrayList<>();
        for (Map.Entry<K, MvccVersionChain<V>> entry : rows.entrySet()) {
            entry.getValue().visibleValue(snapshot, catalog)
                    .ifPresent(value -> visibleRows.add(new MvccRow<>(entry.getKey(), value)));
        }
        throwIfAnyPrunedHistoryWouldHaveBeenVisible(snapshot, catalog);
        return MvccScan.fromVisibleRows(visibleRows);
    }

    public synchronized int visibleRowCount(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        try (MvccScan<K, V> scan = openScan(snapshot, catalog)) {
            return scan.visibleRowCount();
        }
    }

    public synchronized void update(K key, V value, MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        chainForExistingKey(key).update(value, transaction, snapshot, catalog);
    }

    public synchronized void delete(K key, MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        chainForExistingKey(key).delete(transaction, snapshot, catalog);
    }

    public synchronized int physicalVersionCount(K key) {
        MvccVersionChain<V> chain = rows.get(key);
        return chain == null ? 0 : chain.versionCount();
    }

    public synchronized int logicalRowCount() {
        return rows.size();
    }

    public synchronized int physicalVersionCount() {
        int total = 0;
        for (MvccVersionChain<V> chain : rows.values()) {
            total += chain.versionCount();
        }
        return total;
    }

    public synchronized int deadVersionEstimate(MvccCommitSequence oldestVisibleThrough, MvccTransactionCatalog catalog) {
        int total = 0;
        for (MvccVersionChain<V> chain : rows.values()) {
            total += chain.deadVersionEstimate(oldestVisibleThrough, catalog);
        }
        return total;
    }

    public synchronized boolean mayHaveVisibleIndexedValue(
            K key,
            Object indexKey,
            VersionedIndexKeyExtractor<V> extractor,
            MvccCommitSequence oldestVisibleThrough,
            MvccTransactionCatalog catalog) {
        MvccVersionChain<V> chain = rows.get(key);
        return chain != null && chain.mayHaveVisibleIndexedValue(indexKey, extractor, oldestVisibleThrough, catalog);
    }

    public synchronized MvccCleanupResult cleanup(MvccTransactionManager transactionManager) {
        MvccCommitSequence oldestVisibleThrough = transactionManager.oldestActiveVisibleThrough();
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
}
