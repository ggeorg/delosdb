package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.store.MvccCandidateIndex;
import io.github.ggeorg.delosdb.storage.mvcc.store.PageVolumeMvccStateStore;

import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexFallbackReason;
import org.apache.derby.iapi.store.types.DelosStorageOrderedIndexKey;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.store.types.StoreValueCopySupport;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Candidate/ordered index maintenance for the inherited Derby-to-MVCC bridge.
 *
 * <p>This class is deliberately package-private and behavior-preserving.  It
 * keeps the legacy diagnostic candidate index and the typed ordered-index page
 * authority refresh path together while {@link MvccInheritedTable} retains the
 * table-level locking and transaction orchestration.</p>
 */
final class MvccInheritedIndexMaintenance {
    private final PageVolumeMvccStateStore<StoreDataValue[]> pageVolumeStateStore;
    private final MvccCandidateIndex candidateIndex = new MvccCandidateIndex();
    private int pageBackedCandidateIndexRebuildCount;
    private long orderedIndexLookupCount;
    private long orderedIndexHitCount;
    private long orderedIndexFallbackCount;
    private final EnumMap<DelosStorageOrderedIndexFallbackReason, Long> orderedIndexFallbackReasonCounts =
            new EnumMap<>(DelosStorageOrderedIndexFallbackReason.class);
    private long orderedIndexRowIdCount;

    MvccInheritedIndexMaintenance(PageVolumeMvccStateStore<StoreDataValue[]> pageVolumeStateStore) {
        this.pageVolumeStateStore = Objects.requireNonNull(pageVolumeStateStore, "pageVolumeStateStore");
    }

    void clear() {
        candidateIndex.clear();
    }

    void rebuildFromCommittedRows() {
        rebuildFromRows(pageVolumeStateStore.loadVisibleRows());
    }

    void rebuildFromRows(List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        candidateIndex.rebuildFromVisibleRows(toCandidateRows(rows));
        pageVolumeStateStore.rebuildOrderedIndexPages(toOrderedIndexEntries(rows));
        pageBackedCandidateIndexRebuildCount++;
    }

    Optional<List<Long>> candidateRowIdsFor(int column, String value) {
        return candidateIndex.candidatesFor(column, value);
    }

    Optional<List<Long>> orderedIndexRowIdsFor(int column, String value) {
        return recordOrderedIndexLookup(pageVolumeStateStore.orderedIndexLookupFor(column, value));
    }

    Optional<List<Long>> orderedIndexRowIdsInRangeFor(
            int column,
            String lowerValue,
            boolean lowerInclusive,
            String upperValue,
            boolean upperInclusive) {
        return recordOrderedIndexLookup(pageVolumeStateStore.orderedIndexRangeLookupFor(
                column, lowerValue, lowerInclusive, upperValue, upperInclusive));
    }

    void recordOrderedIndexFallbackForTesting(DelosStorageOrderedIndexFallbackReason reason) {
        recordOrderedIndexFallback(reason);
    }

    int candidateIndexKeyCountForTesting() {
        return candidateIndex.indexedKeyCountForTesting();
    }

    int pageBackedCandidateIndexRebuildCountForTesting() {
        return pageBackedCandidateIndexRebuildCount;
    }

    long orderedIndexPageCountForTesting() {
        return pageVolumeStateStore.orderedIndexPageCount();
    }

    long orderedIndexEntryCountForTesting() {
        return pageVolumeStateStore.orderedIndexEntryCount();
    }

    int orderedIndexDistinctKeyCountForTesting() {
        return pageVolumeStateStore.orderedIndexDistinctKeyCount();
    }

    long orderedIndexRebuildCountForTesting() {
        return pageVolumeStateStore.orderedIndexRebuildCount();
    }

    List<String> orderedIndexEntrySummariesForTesting() {
        return pageVolumeStateStore.orderedIndexEntrySummaries();
    }

    long orderedIndexLookupCountForTesting() {
        return orderedIndexLookupCount;
    }

    long orderedIndexHitCountForTesting() {
        return orderedIndexHitCount;
    }

    long orderedIndexFallbackCountForTesting() {
        return orderedIndexFallbackCount;
    }

    long orderedIndexFallbackReasonCountForTesting(DelosStorageOrderedIndexFallbackReason reason) {
        return orderedIndexFallbackReasonCounts.getOrDefault(reason, 0L);
    }

    List<String> orderedIndexFallbackReasonSummariesForTesting() {
        return orderedIndexFallbackReasonCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0L)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().name() + ":" + entry.getValue())
                .toList();
    }

    long orderedIndexRowIdCountForTesting() {
        return orderedIndexRowIdCount;
    }

    int orderedIndexCandidateParityErrorCountForTesting() {
        return orderedIndexCandidateParityErrors().size();
    }

    List<String> orderedIndexCandidateParityErrorSummariesForTesting() {
        return orderedIndexCandidateParityErrors();
    }

    DelosStorageOrderedIndexDiagnostics.AuthorityMode orderedIndexAuthorityModeForTesting() {
        return DelosStorageOrderedIndexDiagnostics.AuthorityMode.CURRENT_COMMITTED_ROW_ID_AUTHORITY;
    }

    private Optional<List<Long>> recordOrderedIndexLookup(
            PageVolumeMvccStateStore.OrderedIndexLookupResult lookup) {
        orderedIndexLookupCount++;
        Optional<List<Long>> rowIds = lookup.rowIds();
        if (rowIds.isEmpty()) {
            recordOrderedIndexFallback(toApiFallbackReason(lookup.fallbackReason()));
            return Optional.empty();
        }
        List<Long> ids = rowIds.get();
        orderedIndexRowIdCount += ids.size();
        if (!ids.isEmpty()) {
            orderedIndexHitCount++;
        }
        return Optional.of(ids);
    }

    private void recordOrderedIndexFallback(DelosStorageOrderedIndexFallbackReason reason) {
        if (reason == null) {
            return;
        }
        orderedIndexFallbackCount++;
        orderedIndexFallbackReasonCounts.merge(reason, 1L, Long::sum);
    }

    private static DelosStorageOrderedIndexFallbackReason toApiFallbackReason(
            PageVolumeMvccStateStore.OrderedIndexLookupFallbackReason reason) {
        return switch (reason) {
            case UNSUPPORTED_KEY_OR_TYPE -> DelosStorageOrderedIndexFallbackReason.UNSUPPORTED_KEY_OR_TYPE;
            case MALFORMED_ORDERED_INDEX_SIDECAR ->
                    DelosStorageOrderedIndexFallbackReason.MALFORMED_ORDERED_INDEX_SIDECAR;
            case STALE_OR_MISSING_ORDERED_INDEX_SIDECAR ->
                    DelosStorageOrderedIndexFallbackReason.STALE_OR_MISSING_ORDERED_INDEX_SIDECAR;
        };
    }

    private List<String> orderedIndexCandidateParityErrors() {
        List<String> candidateEntries = candidateIndex.entrySummariesForTesting();
        List<String> orderedEntries = new ArrayList<>(pageVolumeStateStore.orderedIndexEntrySummaries());
        orderedEntries.sort(String::compareTo);
        if (candidateEntries.equals(orderedEntries)) {
            return List.of();
        }

        LinkedHashSet<String> candidateOnly = new LinkedHashSet<>(candidateEntries);
        candidateOnly.removeAll(orderedEntries);
        LinkedHashSet<String> orderedOnly = new LinkedHashSet<>(orderedEntries);
        orderedOnly.removeAll(candidateEntries);

        List<String> errors = new ArrayList<>();
        errors.add("candidate-size:" + candidateEntries.size() + "|ordered-size:" + orderedEntries.size());
        for (String missingOrdered : candidateOnly) {
            errors.add("missing-ordered:" + missingOrdered);
            if (errors.size() >= 20) {
                return List.copyOf(errors);
            }
        }
        for (String missingCandidate : orderedOnly) {
            errors.add("missing-candidate:" + missingCandidate);
            if (errors.size() >= 20) {
                return List.copyOf(errors);
            }
        }
        return List.copyOf(errors);
    }

    private static List<MvccCandidateIndex.CandidateRow> toCandidateRows(
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        if (rows == null) {
            return List.of();
        }
        List<MvccCandidateIndex.CandidateRow> candidates = new ArrayList<>(rows.size());
        for (PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]> row : rows) {
            candidates.add(new MvccCandidateIndex.CandidateRow(row.rowId(), valueKeysRaw(row.values())));
        }
        return List.copyOf(candidates);
    }

    private static List<PageVolumeMvccStateStore.OrderedIndexEntry> toOrderedIndexEntries(
            List<PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]>> rows) {
        if (rows == null) {
            return List.of();
        }
        List<PageVolumeMvccStateStore.OrderedIndexEntry> entries = new ArrayList<>();
        for (PageVolumeMvccStateStore.PersistedRow<StoreDataValue[]> row : rows) {
            List<String> keys = valueKeysTyped(row.values());
            for (int column = 0; column < keys.size(); column++) {
                entries.add(new PageVolumeMvccStateStore.OrderedIndexEntry(column, keys.get(column), row.rowId()));
            }
        }
        return List.copyOf(entries);
    }

    static List<String> valueKeysRaw(StoreDataValue[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(values.length);
        for (StoreDataValue value : values) {
            keys.add(StoreValueCopySupport.rawStringKey(value));
        }
        return List.copyOf(keys);
    }

    private static List<String> valueKeysTyped(StoreDataValue[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(values.length);
        for (StoreDataValue value : values) {
            keys.add(value == null ? null : valueKeyTyped(value));
        }
        return List.copyOf(keys);
    }

    private static String valueKeyTyped(StoreDataValue value) {
        try {
            return DelosStorageOrderedIndexKey.encode(value);
        } catch (StandardException e) {
            throw new IllegalStateException("Cannot derive typed ordered-index key from "
                    + value.getClass().getName(), e);
        }
    }
}
