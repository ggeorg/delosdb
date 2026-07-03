package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Conservative logical MVCC candidate index.
 *
 * <p>The index only narrows row ids. It is never a visibility authority and it
 * deliberately tolerates stale candidates.</p>
 */
public final class MvccCandidateIndex {
    private final Map<ColumnValueKey, LinkedHashSet<Long>> rowIdsByColumnValue = new LinkedHashMap<>();
    private boolean initialized;

    public synchronized void rebuildFromVisibleRows(List<CandidateRow> rows) {
        rowIdsByColumnValue.clear();
        initialized = true;
        recordVisibleRows(rows);
    }

    public synchronized void recordVisibleRows(List<CandidateRow> rows) {
        initialized = true;
        if (rows == null) {
            return;
        }
        for (CandidateRow row : rows) {
            indexRow(row.rowId(), row.columnValues());
        }
    }

    public synchronized void clear() {
        rowIdsByColumnValue.clear();
        initialized = false;
    }

    public synchronized Optional<List<Long>> candidatesFor(int column, String value) {
        if (!initialized) {
            return Optional.empty();
        }
        ColumnValueKey key = new ColumnValueKey(column, value);
        LinkedHashSet<Long> rowIds = rowIdsByColumnValue.get(key);
        if (rowIds == null) {
            return Optional.of(List.of());
        }
        return Optional.of(List.copyOf(rowIds));
    }

    public synchronized int indexedKeyCountForTesting() {
        return rowIdsByColumnValue.size();
    }

    public synchronized List<String> entrySummariesForTesting() {
        List<String> summaries = new ArrayList<>();
        for (Map.Entry<ColumnValueKey, LinkedHashSet<Long>> entry : rowIdsByColumnValue.entrySet()) {
            ColumnValueKey key = entry.getKey();
            for (Long rowId : entry.getValue()) {
                summaries.add("col:" + key.column() + "|key:" + key.value() + "|row:" + rowId);
            }
        }
        summaries.sort(String::compareTo);
        return List.copyOf(summaries);
    }

    private void indexRow(long rowId, List<String> values) {
        if (rowId <= 0L || values == null) {
            return;
        }
        for (int column = 0; column < values.size(); column++) {
            String value = values.get(column);
            if (value == null) {
                continue;
            }
            ColumnValueKey key = new ColumnValueKey(column, value);
            rowIdsByColumnValue.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(rowId);
        }
    }

    public record CandidateRow(long rowId, List<String> columnValues) {
        public CandidateRow {
            if (rowId <= 0L) {
                throw new IllegalArgumentException("candidate row id must be positive: " + rowId);
            }
            columnValues = List.copyOf(Objects.requireNonNull(columnValues, "columnValues"));
        }
    }

    private record ColumnValueKey(int column, String value) {
        private ColumnValueKey {
            if (column < 0) {
                throw new IllegalArgumentException("candidate index column must be non-negative: " + column);
            }
            value = Objects.requireNonNull(value, "value");
        }
    }
}
