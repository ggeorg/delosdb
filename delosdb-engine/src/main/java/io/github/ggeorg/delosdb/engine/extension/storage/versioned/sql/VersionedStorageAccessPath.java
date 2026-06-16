package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import java.util.Objects;

/**
 * Diagnostic description of the access path selected by the experimental
 * delos_mvcc SQL bridge.
 *
 * <p>This is deliberately separate from Derby runtime statistics. It gives the
 * MVCC prototype a PostgreSQL-style proof point: predicates can be planned as a
 * provider-owned table scan or a provider-owned index scan using table/index
 * statistics, while row visibility remains controlled by MVCC snapshots.</p>
 */
public record VersionedStorageAccessPath(
        String tableName,
        String operation,
        String selectedAccessMethod,
        String predicateColumn,
        String selectedIndex,
        long visibleRowCount,
        long physicalVersionCount,
        long deadVersionEstimate,
        long indexCandidateCount,
        long indexVisibleMatchCount,
        long estimatedTableScanCost,
        long estimatedIndexLookupCost
) {
    public static final String TABLE_SCAN = "mvcc-table-scan";
    public static final String INDEX_SCAN = "mvcc-index-scan";

    public VersionedStorageAccessPath {
        tableName = Objects.requireNonNull(tableName, "tableName");
        operation = Objects.requireNonNull(operation, "operation");
        selectedAccessMethod = Objects.requireNonNull(selectedAccessMethod, "selectedAccessMethod");
        predicateColumn = predicateColumn == null ? "" : predicateColumn;
        selectedIndex = selectedIndex == null ? "" : selectedIndex;
        if (visibleRowCount < 0 || physicalVersionCount < 0 || deadVersionEstimate < 0
                || indexCandidateCount < 0 || indexVisibleMatchCount < 0
                || estimatedTableScanCost < 0 || estimatedIndexLookupCost < 0) {
            throw new IllegalArgumentException("versioned storage access path statistics must be non-negative");
        }
    }
}
