package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexKeyExtractor;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;

/**
 * Provider-owned in-memory index for the experimental delos_mvcc storage engine.
 *
 * <p>The index deliberately stores candidate row keys only. It never decides row
 * visibility by itself. Lookup rechecks the authoritative MVCC version chain and
 * the indexed value visible to the supplied snapshot before returning a row.
 * This is the important PostgreSQL-guided rule for this phase: an index entry
 * points to versioned storage, but visibility belongs to the heap/version chain.</p>
 */
public final class DelosMvccIndex<K, V> implements VersionedIndex<K, V> {
    private final VersionedIndexMetadata metadata;
    private final MvccTable<K, V> table;
    private final VersionedIndexKeyExtractor<V> extractor;
    private final Map<Object, LinkedHashSet<K>> candidatesByKey = new TreeMap<>(DelosMvccIndex::compareIndexKeys);

    DelosMvccIndex(
            VersionedIndexMetadata metadata,
            MvccTable<K, V> table,
            VersionedIndexKeyExtractor<V> extractor) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.table = Objects.requireNonNull(table, "table");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    @Override
    public VersionedIndexMetadata metadata() {
        return metadata;
    }

    synchronized void recordCandidate(K rowKey, V rowValue) {
        Object indexKey = extractor.extract(Objects.requireNonNull(rowValue, "rowValue"));
        if (indexKey == null) {
            return;
        }
        candidatesByKey.computeIfAbsent(indexKey, ignored -> new LinkedHashSet<>()).add(Objects.requireNonNull(rowKey, "rowKey"));
    }

    synchronized void buildFrom(MvccScan<K, V> scan) {
        try (scan) {
            while (scan.next()) {
                MvccRow<K, V> row = scan.row();
                recordCandidate(row.key(), row.value());
            }
        }
    }


    @Override
    public synchronized VersionedIndexStats stats(Object indexKey, TxView view) {
        DelosMvccTxView mvccView = requireMvccView(view);
        Set<K> candidateKeys = candidatesByKey.get(indexKey);
        long candidateCount = candidateKeys == null ? 0L : candidateKeys.size();
        long visibleMatches = 0L;
        if (candidateKeys != null) {
            for (K rowKey : candidateKeys) {
                Optional<V> visibleValue = table.read(rowKey, mvccView.snapshot(), mvccView.catalog());
                if (visibleValue.isPresent() && Objects.equals(indexKey, extractor.extract(visibleValue.get()))) {
                    visibleMatches++;
                }
            }
        }
        long estimatedLookupCost = Math.max(1L, candidateCount + 1L);
        return new VersionedIndexStats(candidatesByKey.size(), candidateCount, visibleMatches, estimatedLookupCost);
    }

    @Override
    public synchronized VersionedScan<K, V> lookup(Object indexKey, TxView view) {
        DelosMvccTxView mvccView = requireMvccView(view);
        Set<K> candidateKeys = candidatesByKey.get(indexKey);
        if (candidateKeys == null || candidateKeys.isEmpty()) {
            return new DelosMvccScan<>(MvccScan.fromVisibleRows(List.of()));
        }

        List<MvccRow<K, V>> visibleRows = new ArrayList<>();
        for (K rowKey : candidateKeys) {
            Optional<V> visibleValue = table.read(rowKey, mvccView.snapshot(), mvccView.catalog());
            if (visibleValue.isPresent() && Objects.equals(indexKey, extractor.extract(visibleValue.get()))) {
                visibleRows.add(new MvccRow<>(rowKey, visibleValue.get()));
            }
        }
        return new DelosMvccScan<>(MvccScan.fromVisibleRows(visibleRows));
    }


    synchronized MvccCleanupResult cleanupCandidates(
            MvccCommitSequence oldestVisibleThrough,
            MvccTransactionCatalog catalog) {
        int removed = 0;
        Iterator<Map.Entry<Object, LinkedHashSet<K>>> buckets = candidatesByKey.entrySet().iterator();
        while (buckets.hasNext()) {
            Map.Entry<Object, LinkedHashSet<K>> bucket = buckets.next();
            Object indexKey = bucket.getKey();
            Iterator<K> keys = bucket.getValue().iterator();
            while (keys.hasNext()) {
                K rowKey = keys.next();
                if (!table.mayHaveVisibleIndexedValue(rowKey, indexKey, extractor, oldestVisibleThrough, catalog)) {
                    keys.remove();
                    removed++;
                }
            }
            if (bucket.getValue().isEmpty()) {
                buckets.remove();
            }
        }
        return new MvccCleanupResult(0, removed, 0);
    }

    public synchronized int indexedKeyCount() {
        return candidatesByKey.size();
    }

    public synchronized int candidateCount(Object indexKey) {
        LinkedHashSet<K> candidates = candidatesByKey.get(indexKey);
        return candidates == null ? 0 : candidates.size();
    }

    private static DelosMvccTxView requireMvccView(TxView view) {
        if (view instanceof DelosMvccTxView mvccView) {
            return mvccView;
        }
        throw new IllegalArgumentException("Delos MVCC index requires DelosMvccTxView");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareIndexKeys(Object left, Object right) {
        if (left == right) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        int classCompare = left.getClass().getName().compareTo(right.getClass().getName());
        if (classCompare != 0) {
            return classCompare;
        }
        return Comparator.comparing(Object::toString).compare(left, right);
    }
}
