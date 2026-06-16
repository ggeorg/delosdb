package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
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
    public void insert(K key, V value, TxContext transaction) {
        DelosMvccTxContext context = requireMvccContext(transaction);
        table.insert(key, value, context.transaction());
        if (shouldLog()) {
            storageLog.appendInsert(metadata, context.transactionId(), key, value);
        }
    }

    @Override
    public void update(K key, V value, TxContext transaction) {
        DelosMvccTxContext context = requireMvccContext(transaction);
        table.update(key, value, context.transaction(), context.snapshot(), context.catalog());
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
        return new VersionedTableStats(table.logicalRowCount(), visibleRows, table.physicalVersionCount(), 0L);
    }

    public MvccCleanupResult cleanup(MvccTransactionManager transactionManager) {
        return table.cleanup(transactionManager);
    }

    private boolean shouldLog() {
        return storageLog.isEnabled() && !loggingSuppressed.getAsBoolean();
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
