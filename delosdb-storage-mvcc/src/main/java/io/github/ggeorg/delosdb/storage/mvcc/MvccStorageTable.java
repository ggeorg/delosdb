package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedWriteConflictException;

import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosCostableTableAccess;
import org.apache.derby.iapi.store.types.DelosFilterableTableAccess;
import org.apache.derby.iapi.store.types.DelosIndexAccess;
import org.apache.derby.iapi.store.types.DelosIndexStats;
import org.apache.derby.iapi.store.types.DelosIndexableTableAccess;
import org.apache.derby.iapi.store.types.DelosMutationPreparation;
import org.apache.derby.iapi.store.types.DelosMutationResult;
import org.apache.derby.iapi.store.types.DelosMvccMutationReservation;
import org.apache.derby.iapi.store.types.DelosMvccReservableTableAccess;
import org.apache.derby.iapi.store.types.DelosPredicate;
import org.apache.derby.iapi.store.types.DelosPredicateOperator;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRange;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.iapi.store.types.DelosTableCapabilities;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableCostEstimate;
import org.apache.derby.iapi.store.types.DelosTableGuarantee;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;

/** Storage-api table facade over a native {@link VersionedTable}. */
public final class MvccStorageTable
        implements DelosFilterableTableAccess,
        DelosIndexableTableAccess,
        DelosMvccReservableTableAccess,
        DelosCostableTableAccess {
    private static final ConcurrentMap<MutationReservationKey, DelosMvccMutationReservation> MUTATION_RESERVATIONS =
            new ConcurrentHashMap<>();

    private final DelosTableIdentity identity;
    private final DelosTableShape rowShape;
    private final VersionedTable<Long, List<Object>> table;
    private final Map<String, IndexBinding> indexesByName;
    private final Map<String, IndexBinding> indexesByColumnName;

    MvccStorageTable(
            DelosTableIdentity identity,
            DelosTableShape rowShape,
            VersionedTable<Long, List<Object>> table) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.rowShape = Objects.requireNonNull(rowShape, "rowShape");
        this.table = Objects.requireNonNull(table, "table");
        Map<String, IndexBinding> byName = new HashMap<>();
        Map<String, IndexBinding> byColumn = new HashMap<>();
        for (VersionedIndexMetadata metadata : table.listIndexes()) {
            VersionedIndex<Long, List<Object>> index = table.openIndex(metadata.indexName());
            IndexBinding binding = new IndexBinding(
                    metadata.indexName(),
                    metadata.indexedColumnName(),
                    metadata.unique(),
                    index);
            byName.put(normalize(binding.indexName()), binding);
            byColumn.put(normalize(binding.columnName()), binding);
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
                DelosTableCapability.MUTABLE,
                DelosTableCapability.COSTABLE);
    }

    @Override
    public Set<DelosTableGuarantee> guarantees() {
        return Set.of(
                DelosTableGuarantee.SNAPSHOT_ISOLATION,
                DelosTableGuarantee.DURABLE_RECOVERY_LOG);
    }

    @Override
    public DelosTableCostEstimate estimateTableCost(DelosAccessContext context) {
        requirePhysicalAccess(context);
        VersionedTableStats tableStats = table.stats(txView(context));
        return new DelosTableCostEstimate(
                tableStats.logicalRowCount(),
                tableStats.visibleRowCount(),
                tableStats.physicalVersionCount(),
                tableStats.deadVersionEstimate(),
                estimateTableScanCost(tableStats));
    }

    @Override
    public DelosScan scan(
            DelosAccessContext context,
            List<DelosPredicate> mutableFilters,
            DelosProjection projection) {
        requirePhysicalAccess(context);
        Objects.requireNonNull(mutableFilters, "mutableFilters");
        Objects.requireNonNull(projection, "projection");

        List<DelosPredicate> remainingFilters = new ArrayList<>(mutableFilters);
        TxView view = txView(context);
        VersionedTableStats tableStats = table.stats(view);
        long tableScanCost = estimateTableScanCost(tableStats);
        Optional<PushedEquality> pushedEquality = findPushedEquality(remainingFilters);
        List<VersionedRow<Long, List<Object>>> visibleRows;

        if (pushedEquality.isPresent()) {
            PushedEquality equality = pushedEquality.get();
            IndexBinding index = indexesByColumnName.get(normalize(equality.columnName()));
            if (index != null) {
                VersionedIndexStats indexStats = index.index().stats(equality.value(), view);
                if (index.uniqueLookup() || indexStats.estimatedLookupCost() <= tableScanCost) {
                    visibleRows = collect(index.index().lookup(equality.value(), view));
                } else {
                    visibleRows = scanAllWhere(equality.columnIndex(), equality.value(), view);
                }
            } else {
                visibleRows = scanAllWhere(equality.columnIndex(), equality.value(), view);
            }
            remainingFilters.remove(equality.predicate());
        } else {
            visibleRows = collect(table.openScan(view));
        }

        if (!remainingFilters.isEmpty()) {
            visibleRows = applyRemainingFilters(visibleRows, remainingFilters);
        }
        mutableFilters.clear();
        return new MvccStorageScan(project(visibleRows, projection));
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
        TxContext tx = context.require(MvccStorageTransaction.TX_CONTEXT_KEY);
        long rowKey = MvccStorageLocator.requireLong(row.rowIdentity().orElseThrow(() ->
                new IllegalArgumentException("delos_mvcc insert requires an MVCC row identity")));
        table.insert(rowKey, MvccStorageRow.nativeValues(row), tx);
        return DelosMutationResult.inserted(MvccStorageLocator.of(rowKey));
    }

    @Override
    public DelosMutationPreparation validateMutable(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity) {
        requirePhysicalAccess(context);
        long rowKey = MvccStorageLocator.requireLong(rowIdentity);
        if (table.read(rowKey, txView(context)).isEmpty()) {
            return DelosMutationPreparation.notMutable(
                    rowIdentity,
                    "delos_mvcc row identity is not visible in the current mutation view");
        }
        return DelosMutationPreparation.mutable(
                rowIdentity,
                "delos_mvcc row identity is visible in the current mutation view");
    }

    @Override
    public DelosMvccMutationReservation reserveMutation(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity) {
        DelosMutationPreparation validation = validateMutable(context, rowIdentity);
        TxContext tx = context.require(MvccStorageTransaction.TX_CONTEXT_KEY);
        if (!validation.mutable()) {
            return DelosMvccMutationReservation.notReserved(rowIdentity, tx.transactionId(), validation.message());
        }

        long rowKey = MvccStorageLocator.requireLong(rowIdentity);
        MutationReservationKey key = new MutationReservationKey(identity, rowKey);
        DelosMvccMutationReservation reservation = DelosMvccMutationReservation.reserved(
                rowIdentity,
                tx.transactionId(),
                "delos_mvcc row reserved for mutation by transaction " + tx.transactionId());
        DelosMvccMutationReservation existing = MUTATION_RESERVATIONS.putIfAbsent(key, reservation);
        if (existing == null || existing.transactionId() == tx.transactionId()) {
            return existing == null ? reservation : existing;
        }
        throw new VersionedWriteConflictException("delos_mvcc row " + rowKey
                + " is reserved for mutation by active transaction " + existing.transactionId()
                + "; requester transaction " + tx.transactionId());
    }

    @Override
    public void completeMutationReservations(DelosAccessContext context, boolean committed) {
        requirePhysicalAccess(context);
        long transactionId = context.require(MvccStorageTransaction.TX_CONTEXT_KEY).transactionId();
        MUTATION_RESERVATIONS.entrySet().removeIf(entry -> entry.getValue().transactionId() == transactionId);
    }

    @Override
    public DelosMutationPreparation prepareMutation(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity) {
        DelosMvccMutationReservation reservation = reserveMutation(context, rowIdentity);
        if (!reservation.reserved()) {
            return DelosMutationPreparation.notMutable(rowIdentity, reservation.message());
        }
        return DelosMutationPreparation.prepared(rowIdentity, reservation.message());
    }

    @Override
    public DelosMutationResult update(
            DelosAccessContext context,
            DelosRowIdentity rowIdentity,
            DelosRow replacement) {
        requirePreparedMutation(context, rowIdentity);
        TxContext tx = context.require(MvccStorageTransaction.TX_CONTEXT_KEY);
        table.update(MvccStorageLocator.requireLong(rowIdentity), MvccStorageRow.nativeValues(replacement), tx);
        return DelosMutationResult.affected(1);
    }

    @Override
    public DelosMutationResult delete(DelosAccessContext context, DelosRowIdentity rowIdentity) {
        requirePreparedMutation(context, rowIdentity);
        TxContext tx = context.require(MvccStorageTransaction.TX_CONTEXT_KEY);
        table.delete(MvccStorageLocator.requireLong(rowIdentity), tx);
        return DelosMutationResult.affected(1);
    }

    VersionedTable<Long, List<Object>> nativeTable() {
        return table;
    }

    private Optional<PushedEquality> findPushedEquality(List<DelosPredicate> mutableFilters) {
        for (DelosPredicate predicate : mutableFilters) {
            if (predicate.operator() == DelosPredicateOperator.EQUAL && predicate.operands().size() == 1) {
                Object nativePredicateValue = MvccStorageRow.nativeValue(predicate.operands().get(0));
                if (nativePredicateValue == null) {
                    continue;
                }
                int columnIndex = MvccStorageRow.columnIndexOrNegative(rowShape, predicate.columnName());
                if (columnIndex >= 0) {
                    return Optional.of(new PushedEquality(
                            predicate,
                            rowShape.columns().get(columnIndex).name(),
                            columnIndex,
                            nativePredicateValue));
                }
            }
        }
        return Optional.empty();
    }

    private List<VersionedRow<Long, List<Object>>> scanAllWhere(int columnIndex, Object predicateValue, TxView view) {
        List<VersionedRow<Long, List<Object>>> rows = new ArrayList<>();
        for (VersionedRow<Long, List<Object>> row : collect(table.openScan(view))) {
            if (Objects.equals(predicateValue, row.value().get(columnIndex))) {
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    private List<VersionedRow<Long, List<Object>>> applyRemainingFilters(
            List<VersionedRow<Long, List<Object>>> rows,
            List<DelosPredicate> filters) {
        List<VersionedRow<Long, List<Object>>> filteredRows = new ArrayList<>(rows);
        for (DelosPredicate predicate : filters) {
            if (!hasSupportedOperandShape(predicate)) {
                throw new IllegalArgumentException("Unsupported delos_mvcc storage-api predicate: " + predicate);
            }
            int columnIndex = MvccStorageRow.columnIndexOrNegative(rowShape, predicate.columnName());
            if (columnIndex < 0) {
                throw new IllegalArgumentException("Unknown delos_mvcc predicate column: " + predicate.columnName());
            }
            filteredRows.removeIf(row -> !predicateMatches(predicate, row.value().get(columnIndex)));
        }
        return List.copyOf(filteredRows);
    }

    private List<DelosRow> project(List<VersionedRow<Long, List<Object>>> rows, DelosProjection projection) {
        List<Integer> indexes = MvccStorageRow.projectionIndexes(rowShape, projection);
        List<DelosRow> projectedRows = new ArrayList<>(rows.size());
        for (VersionedRow<Long, List<Object>> row : rows) {
            projectedRows.add(MvccStorageRow.delosRow(row, indexes));
        }
        return List.copyOf(projectedRows);
    }

    private void requirePreparedMutation(DelosAccessContext context, DelosRowIdentity rowIdentity) {
        DelosMutationPreparation preparation = prepareMutation(context, rowIdentity);
        if (!preparation.prepared()) {
            throw new IllegalStateException("delos_mvcc row identity is not prepared for mutation: "
                    + preparation.message());
        }
    }

    private static void requirePhysicalAccess(DelosAccessContext context) {
        if (!Objects.requireNonNull(context, "context").physicalAccessAllowed()) {
            throw new IllegalStateException("Physical delos_mvcc table access is not allowed by context");
        }
    }

    private static TxView txView(DelosAccessContext context) {
        return context.find(MvccStorageTransaction.TX_VIEW_KEY)
                .orElseGet(() -> context.require(MvccStorageTransaction.TX_CONTEXT_KEY).currentView());
    }

    private static long estimateTableScanCost(VersionedTableStats tableStats) {
        return Math.max(1L, tableStats.visibleRowCount() + tableStats.deadVersionEstimate());
    }

    private static boolean hasSupportedOperandShape(DelosPredicate predicate) {
        return switch (predicate.operator()) {
            case IS_NULL, IS_NOT_NULL -> predicate.operands().isEmpty();
            default -> predicate.operands().size() == 1;
        };
    }

    private static boolean predicateMatches(DelosPredicate predicate, Object actual) {
        if (predicate.operator() == DelosPredicateOperator.IS_NULL) {
            return actual == null;
        }
        if (predicate.operator() == DelosPredicateOperator.IS_NOT_NULL) {
            return actual != null;
        }
        Object expected = MvccStorageRow.nativeValue(predicate.operands().get(0));
        return switch (predicate.operator()) {
            case EQUAL -> expected != null && Objects.equals(expected, actual);
            case NOT_EQUAL -> actual != null && expected != null && !Objects.equals(expected, actual);
            case LESS_THAN -> actual != null && expected != null && compareNative(actual, expected) < 0;
            case LESS_THAN_OR_EQUAL -> actual != null && expected != null && compareNative(actual, expected) <= 0;
            case GREATER_THAN -> actual != null && expected != null && compareNative(actual, expected) > 0;
            case GREATER_THAN_OR_EQUAL -> actual != null && expected != null && compareNative(actual, expected) >= 0;
            default -> throw new IllegalArgumentException(
                    "Unsupported delos_mvcc storage-api predicate operator: " + predicate.operator());
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

    private static List<VersionedRow<Long, List<Object>>> collect(VersionedScan<Long, List<Object>> scan) {
        List<VersionedRow<Long, List<Object>>> rows = new ArrayList<>();
        try (scan) {
            while (scan.next()) {
                rows.add(scan.row());
            }
        }
        return List.copyOf(rows);
    }

    static String normalize(String value) {
        return Objects.requireNonNull(value, "value").trim().toUpperCase(Locale.ROOT);
    }

    private record PushedEquality(
            DelosPredicate predicate,
            String columnName,
            int columnIndex,
            Object value) {
    }

    private record MutationReservationKey(DelosTableIdentity tableIdentity, long rowKey) {
        private MutationReservationKey {
            Objects.requireNonNull(tableIdentity, "tableIdentity");
        }
    }

    private record IndexBinding(
            String indexName,
            String columnName,
            boolean uniqueLookup,
            VersionedIndex<Long, List<Object>> index) {
        private IndexBinding {
            if (indexName == null || indexName.isBlank()) {
                throw new IllegalArgumentException("indexName must not be blank");
            }
            if (columnName == null || columnName.isBlank()) {
                throw new IllegalArgumentException("columnName must not be blank");
            }
            Objects.requireNonNull(index, "index");
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
            VersionedIndexStats stats = binding.index().statsRange(null, true, null, true, txView(context));
            return new DelosIndexStats(stats.visibleMatchCount(), stats.indexedKeyCount());
        }

        @Override
        public DelosScan scan(DelosAccessContext context, DelosRange range, DelosProjection projection) {
            requirePhysicalAccess(context);
            if (!normalize(range.columnName()).equals(normalize(binding.columnName()))) {
                throw new IllegalArgumentException("Index " + binding.indexName()
                        + " cannot scan range for column " + range.columnName());
            }
            List<VersionedRow<Long, List<Object>>> rows = collect(binding.index().lookupRange(
                    range.lowerBound().map(MvccStorageRow::nativeValue).orElse(null),
                    range.lowerInclusive(),
                    range.upperBound().map(MvccStorageRow::nativeValue).orElse(null),
                    range.upperInclusive(),
                    txView(context)));
            return new MvccStorageScan(project(rows, projection));
        }

        @Override
        public void close() {
        }
    }
}
