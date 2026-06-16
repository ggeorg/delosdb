package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndex;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexKeyExtractor;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedIndexMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;

/** Adapter from the DelosDB VersionedStorageProvider SPI to the MVCC kernel. */
public final class DelosMvccTable<K, V> implements VersionedTable<K, V> {
    private static final BooleanSupplier NEVER_SUPPRESS_LOGGING = () -> false;

    private final VersionedTableMetadata metadata;
    private final MvccTable<K, V> table;
    private final DelosMvccStorageLog storageLog;
    private final BooleanSupplier loggingSuppressed;
    private final Map<String, DelosMvccIndex<K, V>> indexes = new LinkedHashMap<>();

    DelosMvccTable(VersionedTableMetadata metadata, MvccTable<K, V> table) {
        this(metadata, table, DelosMvccStorageLog.disabled(), NEVER_SUPPRESS_LOGGING);
    }

    DelosMvccTable(
            VersionedTableMetadata metadata,
            MvccTable<K, V> table,
            DelosMvccStorageLog storageLog,
            BooleanSupplier loggingSuppressed) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.table = Objects.requireNonNull(table, "table");
        this.storageLog = Objects.requireNonNull(storageLog, "storageLog");
        this.loggingSuppressed = Objects.requireNonNull(loggingSuppressed, "loggingSuppressed");
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
        table.insert(key, value, context.transaction());
        recordIndexCandidates(key, value);
        if (shouldLog()) {
            storageLog.appendInsert(metadata, context.transactionId(), key, value);
        }
    }

    @Override
    public void update(K key, V value, TxContext transaction) {
        DelosMvccTxContext context = requireMvccContext(transaction);
        table.update(key, value, context.transaction(), context.snapshot(), context.catalog());
        recordIndexCandidates(key, value);
        if (shouldLog()) {
            storageLog.appendUpdate(metadata, context.transactionId(), key, value);
        }
    }

    @Override
    public void delete(K key, TxContext transaction) {
        DelosMvccTxContext context = requireMvccContext(transaction);
        table.delete(key, context.transaction(), context.snapshot(), context.catalog());
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
        MvccCommitSequence oldestVisibleThrough = transactionManager.oldestActiveVisibleThrough();
        MvccCleanupResult result = table.cleanup(transactionManager);
        for (DelosMvccIndex<K, V> index : indexes.values()) {
            result = result.plus(index.cleanupCandidates(oldestVisibleThrough, transactionManager));
        }
        return result;
    }

    private synchronized void recordIndexCandidates(K key, V value) {
        for (DelosMvccIndex<K, V> index : indexes.values()) {
            index.recordCandidate(key, value);
        }
    }

    private boolean shouldLog() {
        return storageLog.isEnabled() && !loggingSuppressed.getAsBoolean();
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
