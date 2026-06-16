package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedScan;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;

/** Adapter from the DelosDB VersionedStorageProvider SPI to the MVCC kernel. */
public final class DelosMvccTable<K, V> implements VersionedTable<K, V> {
    private final VersionedTableMetadata metadata;
    private final MvccTable<K, V> table;

    DelosMvccTable(VersionedTableMetadata metadata, MvccTable<K, V> table) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.table = Objects.requireNonNull(table, "table");
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
    }

    @Override
    public void update(K key, V value, TxContext transaction) {
        DelosMvccTxContext context = requireMvccContext(transaction);
        table.update(key, value, context.transaction(), context.snapshot(), context.catalog());
    }

    @Override
    public void delete(K key, TxContext transaction) {
        DelosMvccTxContext context = requireMvccContext(transaction);
        table.delete(key, context.transaction(), context.snapshot(), context.catalog());
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
