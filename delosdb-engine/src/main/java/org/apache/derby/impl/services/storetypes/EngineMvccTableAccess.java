/*

   Derby - Class org.apache.derby.impl.services.storetypes.EngineMvccTableAccess

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derby.impl.services.storetypes;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosContextKey;
import org.apache.derby.iapi.store.types.DelosFilterableTableAccess;
import org.apache.derby.iapi.store.types.DelosIndexAccess;
import org.apache.derby.iapi.store.types.DelosIndexStats;
import org.apache.derby.iapi.store.types.DelosIndexableTableAccess;
import org.apache.derby.iapi.store.types.DelosMutationResult;
import org.apache.derby.iapi.store.types.DelosMutableTableAccess;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosPredicateOperator;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRange;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableCapabilities;
import org.apache.derby.iapi.store.types.DelosTableGuarantee;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.iapi.store.types.StoreDataValue;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.shared.common.error.StandardException;

/**
 * Engine-side MVCC table-access adapter for the C21 contract proof.
 *
 * <p>This is not a new storage engine.  It adapts the existing
 * {@link VersionedTable}, provider-owned indexes, and
 * {@link VersionedStorageExecutionBridge} to the C20 capability interfaces so
 * planned SQL execution can cross the store-neutral boundary before it reaches
 * the existing MVCC provider.</p>
 */
public final class EngineMvccTableAccess
        implements DelosFilterableTableAccess, DelosIndexableTableAccess, DelosMutableTableAccess {
    public static final String PROVIDER_NAME = "delos_mvcc";

    public static final DelosContextKey<TxContext> TX_CONTEXT_KEY =
            DelosContextKey.of("delosdb.mvcc.tx.context", TxContext.class);

    public static final DelosContextKey<TxView> TX_VIEW_KEY =
            DelosContextKey.of("delosdb.mvcc.tx.view", TxView.class);

    private final DelosTableIdentity identity;
    private final DelosTableShape rowShape;
    private final VersionedTable<Long, List<Object>> table;
    private final VersionedStorageExecutionBridge executionBridge;
    private final Map<String, IndexBinding> indexesByName;
    private final Map<String, IndexBinding> indexesByColumnName;
    private volatile VersionedStorageAccessPath lastAccessPath;

    public EngineMvccTableAccess(
            DelosTableIdentity identity,
            DelosTableShape rowShape,
            VersionedTable<Long, List<Object>> table,
            VersionedStorageExecutionBridge executionBridge,
            List<IndexBinding> indexes) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.rowShape = Objects.requireNonNull(rowShape, "rowShape");
        this.table = Objects.requireNonNull(table, "table");
        this.executionBridge = Objects.requireNonNull(executionBridge, "executionBridge");
        Map<String, IndexBinding> byName = new HashMap<>();
        Map<String, IndexBinding> byColumn = new HashMap<>();
        for (IndexBinding index : indexes) {
            byName.put(normalize(index.indexName()), index);
            byColumn.put(normalize(index.columnName()), index);
        }
        this.indexesByName = Map.copyOf(byName);
        this.indexesByColumnName = Map.copyOf(byColumn);
    }

    @Override
    public DelosTableIdentity identity() {
        return identity;
    }

    @Override
    public DelosTableShape rowShape() {
        return rowShape;
    }

    @Override
    public DelosTableCapabilities capabilities() {
        return DelosTableCapabilities.of(
                DelosTableCapability.FILTERABLE,
                DelosTableCapability.PROJECTABLE,
                DelosTableCapability.INDEXABLE,
                DelosTableCapability.MUTABLE);
    }

    @Override
    public Set<DelosTableGuarantee> guarantees() {
        return Set.of(
                DelosTableGuarantee.SNAPSHOT_ISOLATION,
                DelosTableGuarantee.DURABLE_RECOVERY_LOG);
    }

    @Override
    public DelosScan scan(
            DelosAccessContext context,
            List<DelosPredicate> filters,
            DelosProjection projection) {
        requirePhysicalAccess(context);
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(projection, "projection");
        List<DelosPredicate> remainingFilters = new ArrayList<>(filters);
        TxView view = txView(context);
        VersionedTableStats tableStats = executionBridge.stats(table, view);
        long tableScanCost = estimateTableScanCost(tableStats);

        Optional<PushedEquality> pushedEquality = findPushedEquality(remainingFilters);
        List<VersionedRow<Long, List<Object>>> visibleRows;
        String predicateColumnName = "";
        String indexName = "";
        String accessType = VersionedStorageAccessPath.TABLE_SCAN;
        long candidateCount = 0L;
        long visibleMatchCount = 0L;
        long indexLookupCost = 0L;

        if (pushedEquality.isPresent()) {
            PushedEquality equality = pushedEquality.get();
            predicateColumnName = equality.columnName();
            IndexBinding index = indexesByColumnName.get(normalize(equality.columnName()));
            if (index != null) {
                VersionedIndexStats indexStats = executionBridge.indexStats(index.index(), equality.value(), view);
                indexLookupCost = indexStats.estimatedLookupCost();
                if (index.uniqueLookup() || indexLookupCost <= tableScanCost) {
                    visibleRows = executionBridge.lookup(index.index(), equality.value(), view);
                    accessType = VersionedStorageAccessPath.INDEX_SCAN;
                    indexName = index.indexName();
                    candidateCount = indexStats.candidateCount();
                    visibleMatchCount = indexStats.visibleMatchCount();
                } else {
                    visibleRows = scanAllWhere(equality.columnIndex(), equality.value(), view);
                    indexName = index.indexName();
                    candidateCount = indexStats.candidateCount();
                    visibleMatchCount = indexStats.visibleMatchCount();
                }
            } else {
                visibleRows = scanAllWhere(equality.columnIndex(), equality.value(), view);
                visibleMatchCount = visibleRows.size();
            }
            remainingFilters.remove(equality.predicate());
        } else {
            visibleRows = executionBridge.scanAll(table, view);
            visibleMatchCount = visibleRows.size();
        }

        if (!remainingFilters.isEmpty()) {
            visibleRows = applyRemainingFilters(visibleRows, remainingFilters);
            visibleMatchCount = visibleRows.size();
        }

        lastAccessPath = new VersionedStorageAccessPath(
                identity.qualifiedName(),
                predicateColumnName.isEmpty() ? "contract-select-all" : "select-where",
                accessType,
                predicateColumnName,
                indexName,
                tableStats.visibleRowCount(),
                tableStats.physicalVersionCount(),
                tableStats.deadVersionEstimate(),
                candidateCount,
                visibleMatchCount,
                tableScanCost,
                indexLookupCost);
        return new MaterializedScan(project(visibleRows, projection));
    }

    @Override
    public DelosIndexAccess openIndex(DelosAccessContext context, String indexName) {
        requirePhysicalAccess(context);
        IndexBinding binding = indexesByName.get(normalize(indexName));
        if (binding == null) {
            throw new IllegalArgumentException("Unknown delos_mvcc index: " + indexName);
        }
        return new MvccIndexAccess(binding);
    }

    @Override
    public DelosMutationResult insert(DelosAccessContext context, DelosRow row) {
        requirePhysicalAccess(context);
        TxContext tx = context.require(TX_CONTEXT_KEY);
        long rowKey = requireNativeRowKey(row.rowIdentity().orElseThrow(() ->
                new IllegalArgumentException("delos_mvcc insert requires an MVCC row identity")));
        executionBridge.insert(table, rowKey, nativeValues(row), tx);
        return DelosMutationResult.inserted(new MvccRowIdentity(rowKey));
    }

    @Override
    public DelosMutationResult update(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity,
            DelosRow replacement) {
        requirePhysicalAccess(context);
        TxContext tx = context.require(TX_CONTEXT_KEY);
        executionBridge.update(table, requireNativeRowKey(rowIdentity), nativeValues(replacement), tx);
        return DelosMutationResult.affected(1);
    }

    @Override
    public DelosMutationResult delete(DelosAccessContext context, DelosRowIdentity rowIdentity) {
        requirePhysicalAccess(context);
        TxContext tx = context.require(TX_CONTEXT_KEY);
        executionBridge.delete(table, requireNativeRowKey(rowIdentity), tx);
        return DelosMutationResult.affected(1);
    }

    public Optional<VersionedStorageAccessPath> lastAccessPath() {
        return Optional.ofNullable(lastAccessPath);
    }

    public static StoreDataValue value(Object value) {
        return new MvccStoreDataValue(value);
    }

    public static Object nativeValue(StoreDataValue value) {
        if (value instanceof MvccStoreDataValue mvccValue) {
            return mvccValue.value();
        }
        if (value instanceof DataValueDescriptor dvd) {
            try {
                return dvd.getObject();
            } catch (StandardException se) {
                throw new IllegalArgumentException("Could not unwrap Derby data value", se);
            }
        }
        return value;
    }

    private Optional<PushedEquality> findPushedEquality(List<DelosPredicate> mutableFilters) {
        for (DelosPredicate predicate : mutableFilters) {
            if (predicate.operator() == DelosPredicateOperator.EQUAL && predicate.operands().size() == 1) {
                int columnIndex = columnIndexOrNegative(predicate.columnName());
                if (columnIndex >= 0) {
                    return Optional.of(new PushedEquality(
                            predicate,
                            rowShape.columns().get(columnIndex).name(),
                            columnIndex,
                            nativeValue(predicate.operands().get(0))));
                }
            }
        }
        return Optional.empty();
    }

    private List<VersionedRow<Long, List<Object>>> applyRemainingFilters(
            List<VersionedRow<Long, List<Object>>> rows,
            List<DelosPredicate> filters) {
        List<VersionedRow<Long, List<Object>>> filteredRows = new ArrayList<>(rows);
        for (DelosPredicate predicate : filters) {
            if (predicate.operands().size() != 1) {
                throw new IllegalArgumentException("Unsupported delos_mvcc native scan leftover predicate: " + predicate);
            }
            int columnIndex = columnIndexOrNegative(predicate.columnName());
            if (columnIndex < 0) {
                throw new IllegalArgumentException("Unknown delos_mvcc predicate column: " + predicate.columnName());
            }
            filteredRows.removeIf(row -> !predicateMatches(predicate, row.value().get(columnIndex)));
        }
        return List.copyOf(filteredRows);
    }

    private List<VersionedRow<Long, List<Object>>> scanAllWhere(int columnIndex, Object predicateValue, TxView view) {
        List<VersionedRow<Long, List<Object>>> rows = new ArrayList<>();
        for (VersionedRow<Long, List<Object>> row : executionBridge.scanAll(table, view)) {
            if (Objects.equals(predicateValue, row.value().get(columnIndex))) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static boolean predicateMatches(DelosPredicate predicate, Object actual) {
        Object expected = nativeValue(predicate.operands().get(0));
        return switch (predicate.operator()) {
            case EQUAL -> Objects.equals(expected, actual);
            case NOT_EQUAL -> !Objects.equals(expected, actual);
            case LESS_THAN -> actual != null && expected != null && compareNative(actual, expected) < 0;
            case LESS_THAN_OR_EQUAL -> actual != null && expected != null && compareNative(actual, expected) <= 0;
            case GREATER_THAN -> actual != null && expected != null && compareNative(actual, expected) > 0;
            case GREATER_THAN_OR_EQUAL -> actual != null && expected != null && compareNative(actual, expected) >= 0;
            default -> throw new IllegalArgumentException(
                    "Unsupported delos_mvcc native scan predicate operator: " + predicate.operator());
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compareNative(Object actual, Object expected) {
        if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
            return Long.compare(actualNumber.longValue(), expectedNumber.longValue());
        }
        if (actual instanceof Comparable comparable && actual.getClass().isInstance(expected)) {
            return comparable.compareTo(expected);
        }
        throw new IllegalArgumentException("Cannot compare delos_mvcc native values "
                + actual.getClass().getName() + " and " + expected.getClass().getName());
    }

    private List<DelosRow> project(List<VersionedRow<Long, List<Object>>> rows, DelosProjection projection) {
        List<Integer> indexes = projectionIndexes(projection);
        List<DelosRow> projectedRows = new ArrayList<>(rows.size());
        for (VersionedRow<Long, List<Object>> row : rows) {
            List<StoreDataValue> values = new ArrayList<>(indexes.size());
            for (int index : indexes) {
                values.add(value(row.value().get(index)));
            }
            projectedRows.add(DelosRow.withIdentity(new MvccRowIdentity(row.key()), values));
        }
        return projectedRows;
    }

    private List<Integer> projectionIndexes(DelosProjection projection) {
        if (projection.allColumns()) {
            List<Integer> indexes = new ArrayList<>(rowShape.columns().size());
            for (int i = 0; i < rowShape.columns().size(); i++) {
                indexes.add(i);
            }
            return indexes;
        }
        List<Integer> indexes = new ArrayList<>(projection.columnNames().size());
        for (String columnName : projection.columnNames()) {
            int index = columnIndexOrNegative(columnName);
            if (index < 0) {
                throw new IllegalArgumentException("Unknown projected delos_mvcc column: " + columnName);
            }
            indexes.add(index);
        }
        return indexes;
    }

    private int columnIndexOrNegative(String columnName) {
        String normalized = normalize(columnName);
        for (int i = 0; i < rowShape.columns().size(); i++) {
            if (normalize(rowShape.columns().get(i).name()).equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    private static void requirePhysicalAccess(DelosAccessContext context) {
        if (!Objects.requireNonNull(context, "context").physicalAccessAllowed()) {
            throw new IllegalStateException("Physical delos_mvcc table access is not allowed by context");
        }
    }

    private static TxView txView(DelosAccessContext context) {
        return context.find(TX_VIEW_KEY)
                .orElseGet(() -> context.require(TX_CONTEXT_KEY).currentView());
    }

    private static long estimateTableScanCost(VersionedTableStats tableStats) {
        return Math.max(1L, tableStats.visibleRowCount() + tableStats.deadVersionEstimate());
    }

    private static long requireNativeRowKey(DelosRowIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!PROVIDER_NAME.equals(identity.providerName())) {
            throw new IllegalArgumentException("Row identity belongs to provider " + identity.providerName());
        }
        Object nativeIdentity = identity.nativeIdentity();
        if (nativeIdentity instanceof Long longKey) {
            return longKey;
        }
        throw new IllegalArgumentException("delos_mvcc row identity must wrap a Long key");
    }

    private static List<Object> nativeValues(DelosRow row) {
        List<Object> values = new ArrayList<>(row.values().size());
        for (StoreDataValue value : row.values()) {
            values.add(nativeValue(value));
        }
        return List.copyOf(values);
    }

    private static String normalize(String value) {
        return Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT);
    }

    private record PushedEquality(
            DelosPredicate predicate,
            String columnName,
            int columnIndex,
            Object value) {
    }

    public record IndexBinding(
            String indexName,
            String columnName,
            boolean uniqueLookup,
            VersionedIndex<Long, List<Object>> index) {
        public IndexBinding {
            if (indexName == null || indexName.isBlank()) {
                throw new IllegalArgumentException("indexName must not be blank");
            }
            if (columnName == null || columnName.isBlank()) {
                throw new IllegalArgumentException("columnName must not be blank");
            }
            Objects.requireNonNull(index, "index");
        }
    }

    private record MvccRowIdentity(Object nativeIdentity) implements DelosRowIdentity {
        @Override
        public String providerName() {
            return PROVIDER_NAME;
        }
    }

    private record MvccStoreDataValue(Object value) implements StoreDataValue {
    }

    private static final class MaterializedScan implements DelosScan {
        private final Iterator<DelosRow> rows;
        private DelosRow current;

        private MaterializedScan(List<DelosRow> rows) {
            this.rows = List.copyOf(rows).iterator();
        }

        @Override
        public boolean next() {
            if (!rows.hasNext()) {
                current = null;
                return false;
            }
            current = rows.next();
            return true;
        }

        @Override
        public DelosRow row() {
            if (current == null) {
                throw new IllegalStateException("scan is not positioned on a row");
            }
            return current;
        }

        @Override
        public void close() {
            current = null;
        }
    }

    private final class MvccIndexAccess implements DelosIndexAccess {
        private final IndexBinding binding;

        private MvccIndexAccess(IndexBinding binding) {
            this.binding = Objects.requireNonNull(binding, "binding");
        }

        @Override
        public String indexName() {
            return binding.indexName();
        }

        @Override
        public DelosIndexStats stats(DelosAccessContext context) {
            requirePhysicalAccess(context);
            VersionedIndexStats stats = executionBridge.indexStatsRange(
                    binding.index(),
                    null,
                    true,
                    null,
                    true,
                    txView(context));
            return new DelosIndexStats(stats.visibleMatchCount(), stats.indexedKeyCount());
        }

        @Override
        public DelosScan scan(DelosAccessContext context, DelosRange range, DelosProjection projection) {
            requirePhysicalAccess(context);
            if (!normalize(range.columnName()).equals(normalize(binding.columnName()))) {
                throw new IllegalArgumentException("Index " + binding.indexName()
                        + " cannot scan range for column " + range.columnName());
            }
            List<VersionedRow<Long, List<Object>>> rows = executionBridge.lookupRange(
                    binding.index(),
                    range.lowerBound().map(EngineMvccTableAccess::nativeValue).orElse(null),
                    range.lowerInclusive(),
                    range.upperBound().map(EngineMvccTableAccess::nativeValue).orElse(null),
                    range.upperInclusive(),
                    txView(context));
            return new MaterializedScan(project(rows, projection));
        }

        @Override
        public void close() {
        }
    }
}
