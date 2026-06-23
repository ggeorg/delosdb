package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.io.IOException;
import java.io.UncheckedIOException;


import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexKeyExtractor;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;
import io.github.ggeorg.delosdb.storage.mvcc.durable.DurableMvccSqlRowCodec;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccRowPayload;
import io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable;

/** Adapter from the DelosDB VersionedStorageProvider SPI to the MVCC kernel. */
public final class DelosMvccTable<K, V> implements VersionedTable<K, V> {
    private static final BooleanSupplier NEVER_SUPPRESS_LOGGING = () -> false;

    private final VersionedTableMetadata metadata;
    private final MvccTable<K, V> table;
    private final DelosMvccStorageLog storageLog;
    private final BooleanSupplier loggingSuppressed;
    private final PageBackedMvccTable durableTable;
    private final Map<String, DelosMvccIndex<K, V>> indexes = new LinkedHashMap<>();
    private final Map<Long, List<DurableChange<K, V>>> pendingDurableChanges = new LinkedHashMap<>();
    private boolean hydratingFromDurable;

    DelosMvccTable(VersionedTableMetadata metadata, MvccTable<K, V> table) {
        this(metadata, table, DelosMvccStorageLog.disabled(), NEVER_SUPPRESS_LOGGING);
    }

    DelosMvccTable(
            VersionedTableMetadata metadata,
            MvccTable<K, V> table,
            DelosMvccStorageLog storageLog,
            BooleanSupplier loggingSuppressed) {
        this(metadata, table, storageLog, loggingSuppressed, null);
    }

    DelosMvccTable(
            VersionedTableMetadata metadata,
            MvccTable<K, V> table,
            DelosMvccStorageLog storageLog,
            BooleanSupplier loggingSuppressed,
            PageBackedMvccTable durableTable) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.table = Objects.requireNonNull(table, "table");
        this.storageLog = Objects.requireNonNull(storageLog, "storageLog");
        this.loggingSuppressed = Objects.requireNonNull(loggingSuppressed, "loggingSuppressed");
        this.durableTable = durableTable;
    }

    @Override
    public VersionedTableMetadata metadata() {
        return metadata;
    }

    @Override
    public Optional<V> read(K key, TxView view) {
        DelosMvccTxView mvccView = requireMvccView(view);
        return table.read(key, mvccView.snapshot(), mvccView.catalog());
    }

    @Override
    public VersionedScan<K, V> openScan(TxView view) {
        DelosMvccTxView mvccView = requireMvccView(view);
        return new DelosMvccScan<>(table.openScan(mvccView.snapshot(), mvccView.catalog()));
    }

    @Override
    public synchronized List<VersionedIndexMetadata> listIndexes() {
        List<VersionedIndexMetadata> metadataList = new ArrayList<>();
        for (DelosMvccIndex<K, V> index : indexes.values()) {
            metadataList.add(index.metadata());
        }
        return List.copyOf(metadataList);
    }

    @Override
    public synchronized VersionedIndex<K, V> createIndex(
            VersionedIndexMetadata indexMetadata,
            VersionedIndexKeyExtractor<V> extractor,
            TxView buildView) {
        Objects.requireNonNull(indexMetadata, "indexMetadata");
        Objects.requireNonNull(extractor, "extractor");
        if (!metadata.equals(indexMetadata.table())) {
            throw new IllegalArgumentException("index " + indexMetadata.qualifiedName()
                    + " does not belong to table " + metadata.qualifiedName());
        }
        String indexName = normalizeIndexName(indexMetadata.indexName());
        if (indexes.containsKey(indexName)) {
            throw new IllegalStateException("versioned index already exists: " + indexMetadata.qualifiedName());
        }
        DelosMvccTxView mvccView = requireMvccView(buildView);
        DelosMvccIndex<K, V> index = new DelosMvccIndex<>(indexMetadata, table, extractor);
        index.buildFrom(table.openScan(mvccView.snapshot(), mvccView.catalog()));
        indexes.put(indexName, index);
        return index;
    }

    @Override
    public synchronized VersionedIndex<K, V> openIndex(String indexName) {
        DelosMvccIndex<K, V> index = indexes.get(normalizeIndexName(indexName));
        if (index == null) {
            throw new IllegalStateException("versioned index does not exist: " + metadata.qualifiedName()
                    + "." + normalizeIndexName(indexName));
        }
        return index;
    }

    @Override
    public void insert(K key, V value, TxContext transaction) {
        DelosMvccTxContext context = requireMvccContext(transaction);
        table.insert(key, value, context.transaction(), context.commandSequence());
        recordIndexCandidates(key, value);
        recordDurableChange(context.transactionId(), DurableChange.insert(key, value));
        if (shouldLog()) {
            storageLog.appendInsert(metadata, context.transactionId(), key, value);
        }
    }

    @Override
    public void update(K key, V value, TxContext transaction) {
        DelosMvccTxContext context = requireMvccContext(transaction);
        table.update(key, value, context.transaction(), context.snapshot(), context.catalog(), context.commandSequence());
        recordIndexCandidates(key, value);
        recordDurableChange(context.transactionId(), DurableChange.update(key, value));
        if (shouldLog()) {
            storageLog.appendUpdate(metadata, context.transactionId(), key, value);
        }
    }

    @Override
    public void delete(K key, TxContext transaction) {
        DelosMvccTxContext context = requireMvccContext(transaction);
        table.delete(key, context.transaction(), context.snapshot(), context.catalog(), context.commandSequence());
        recordDurableChange(context.transactionId(), DurableChange.delete(key));
        if (shouldLog()) {
            storageLog.appendDelete(metadata, context.transactionId(), key);
        }
    }

    @Override
    public VersionedTableStats stats(TxView view) {
        DelosMvccTxView mvccView = requireMvccView(view);
        long visibleRows = table.visibleRowCount(mvccView.snapshot(), mvccView.catalog());
        long deadVersions = table.deadVersionEstimate(
                new MvccCommitSequence(mvccView.oldestVisibleTransaction()),
                mvccView.catalog());
        return new VersionedTableStats(table.logicalRowCount(), visibleRows, table.physicalVersionCount(), deadVersions);
    }

    public MvccCleanupResult cleanup(MvccTransactionManager transactionManager) {
        MvccCommitSequence oldestVisibleThrough = transactionManager.oldestRetainedVisibleThrough();
        MvccCleanupResult result = table.cleanup(transactionManager);
        for (DelosMvccIndex<K, V> index : indexes.values()) {
            result = result.plus(index.cleanupCandidates(oldestVisibleThrough, transactionManager));
        }
        return result;
    }

    void hydrateFromDurable(DelosMvccTransactionCoordinator coordinator) {
        if (durableTable == null) {
            return;
        }
        Objects.requireNonNull(coordinator, "coordinator");
        DelosMvccTxContext context = coordinator.begin();
        hydratingFromDurable = true;
        try {
            for (MvccRowPayload payload : durableTable.visibleRows(new MvccCommitSequence(Long.MAX_VALUE))) {
                @SuppressWarnings("unchecked")
                K key = (K) Long.valueOf(payload.key());
                @SuppressWarnings("unchecked")
                V value = (V) DurableMvccSqlRowCodec.decode(payload.value());
                table.insert(key, value, context.transaction());
                recordIndexCandidates(key, value);
            }
            coordinator.commit(context);
        } finally {
            hydratingFromDurable = false;
            pendingDurableChanges.remove(context.transactionId());
        }
    }

    void durableCommit(long transactionId, MvccCommitSequence commitSequence) {
        if (durableTable == null) {
            return;
        }
        List<DurableChange<K, V>> changes = pendingDurableChanges.remove(transactionId);
        if (changes == null || changes.isEmpty()) {
            return;
        }
        try {
            for (DurableChange<K, V> change : changes) {
                change.apply(durableTable, transactionId, commitSequence.value());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist committed delos_mvcc durable changes for "
                    + metadata.qualifiedName(), e);
        }
    }

    void durableAbort(long transactionId) {
        pendingDurableChanges.remove(transactionId);
    }

    List<DelosMvccStorageLog.CheckpointRow> checkpointRows(TxView view) {
        List<DelosMvccStorageLog.CheckpointRow> rows = new ArrayList<>();
        try (VersionedScan<K, V> scan = openScan(view)) {
            while (scan.next()) {
                io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow<K, V> row = scan.row();
                rows.add(new DelosMvccStorageLog.CheckpointRow(
                        metadata,
                        requireCheckpointLongKey(row.key()),
                        requireCheckpointValues(row.value())));
            }
        }
        return List.copyOf(rows);
    }

    private synchronized void recordIndexCandidates(K key, V value) {
        for (DelosMvccIndex<K, V> index : indexes.values()) {
            index.recordCandidate(key, value);
        }
    }

    private synchronized void recordDurableChange(long transactionId, DurableChange<K, V> change) {
        if (durableTable == null || hydratingFromDurable) {
            return;
        }
        pendingDurableChanges.computeIfAbsent(transactionId, ignored -> new ArrayList<>()).add(change);
    }

    private boolean shouldLog() {
        return storageLog.isEnabled() && !loggingSuppressed.getAsBoolean();
    }

    private record DurableChange<K, V>(String operation, K key, V value) {
        private static <K, V> DurableChange<K, V> insert(K key, V value) {
            return new DurableChange<>("insert", key, value);
        }

        private static <K, V> DurableChange<K, V> update(K key, V value) {
            return new DurableChange<>("update", key, value);
        }

        private static <K, V> DurableChange<K, V> delete(K key) {
            return new DurableChange<>("delete", key, null);
        }

        private void apply(PageBackedMvccTable durableTable, long transactionId, long commitSequence) throws IOException {
            String durableKey = requireDurableLongKey(key);
            switch (operation) {
            case "insert" -> durableTable.insertCommitted(
                    durableKey, DurableMvccSqlRowCodec.encode(requireDurableSqlRowValue(value)), transactionId, commitSequence);
            case "update" -> durableTable.updateCommitted(
                    durableKey, DurableMvccSqlRowCodec.encode(requireDurableSqlRowValue(value)), transactionId, commitSequence);
            case "delete" -> durableTable.deleteCommitted(durableKey, transactionId, commitSequence);
            default -> throw new IllegalStateException("Unsupported durable delos_mvcc operation: " + operation);
            }
        }
    }

    private static String requireDurableLongKey(Object key) {
        if (key instanceof Long longKey) {
            return Long.toString(longKey);
        }
        throw new UnsupportedOperationException("page-backed delos_mvcc SQL storage currently supports Long row keys only");
    }

    private static List<Object> requireDurableSqlRowValue(Object value) {
        if (value instanceof List<?> values) {
            return copySqlRowValues(values);
        }
        throw new UnsupportedOperationException("page-backed delos_mvcc SQL storage currently supports List<Object> row values only");
    }

    private static long requireCheckpointLongKey(Object key) {
        if (key instanceof Long longKey) {
            return longKey;
        }
        throw new UnsupportedOperationException("delos_mvcc checkpoint currently supports Long row keys only");
    }

    private static List<Object> requireCheckpointValues(Object value) {
        if (value instanceof List<?> values) {
            return copySqlRowValues(values);
        }
        throw new UnsupportedOperationException("delos_mvcc checkpoint currently supports List<Object> row values only");
    }

    private static List<Object> copySqlRowValues(List<?> values) {
        List<Object> copy = new ArrayList<>(values.size());
        copy.addAll(values);
        return Collections.unmodifiableList(copy);
    }

    private static String normalizeIndexName(String indexName) {
        String trimmed = Objects.requireNonNull(indexName, "indexName").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("indexName must not be blank");
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static DelosMvccTxContext requireMvccContext(TxContext transaction) {
        if (transaction instanceof DelosMvccTxContext context) {
            return context;
        }
        throw new IllegalArgumentException("Delos MVCC table requires DelosMvccTxContext");
    }

    private static DelosMvccTxView requireMvccView(TxView view) {
        if (view instanceof DelosMvccTxView mvccView) {
            return mvccView;
        }
        throw new IllegalArgumentException("Delos MVCC table requires DelosMvccTxView");
    }
}
