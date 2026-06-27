package io.github.ggeorg.delosdb.storage.mvcc;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;

import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;

/**
 * Thin storage-api provider facade over the native Delos MVCC provider.
 *
 * <p>This class deliberately adapts the existing MVCC provider to
 * {@code delosdb-storage-api} contracts. It does not move WAL, checkpoint,
 * vacuum, page-volume, or index implementation code out of this module.</p>
 */
public final class MvccStorageProvider {
    public static final String PROVIDER_NAME = DelosMvccStorageProvider.PROVIDER_NAME;

    private final VersionedStorageProvider delegate;

    /** Creates an in-memory MVCC provider facade. */
    public MvccStorageProvider() {
        this(new DelosMvccStorageProvider());
    }

    private MvccStorageProvider(VersionedStorageProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static MvccStorageProvider inMemory() {
        return new MvccStorageProvider(new DelosMvccStorageProvider());
    }

    public static MvccStorageProvider open(Path storageDirectory) {
        return new MvccStorageProvider(DelosMvccStorageProvider.open(storageDirectory));
    }

    public static MvccStorageProvider openPageBacked(Path storageDirectory) {
        return new MvccStorageProvider(DelosMvccStorageProvider.openPageBacked(storageDirectory));
    }

    public MvccStorageProvider openForDatabase(Path databaseDirectory) {
        return new MvccStorageProvider(delegate.openForDatabase(databaseDirectory));
    }

    public String name() {
        return delegate.name();
    }

    public List<DelosTableIdentity> listTables() {
        return delegate.listTables().stream()
                .map(MvccStorageProvider::tableIdentity)
                .toList();
    }

    public MvccStorageTransaction beginTransaction() {
        return new MvccStorageTransaction(delegate.transactionCoordinator().begin());
    }

    public MvccStorageTransaction refreshTransaction(MvccStorageTransaction transaction) {
        return new MvccStorageTransaction(delegate.transactionCoordinator().refresh(transaction.context()));
    }

    public void commit(MvccStorageTransaction transaction) {
        delegate.transactionCoordinator().commit(transaction.context());
    }

    public void abort(MvccStorageTransaction transaction) {
        delegate.transactionCoordinator().abort(transaction.context());
    }

    public MvccStorageTable createTable(DelosTableIdentity identity, DelosTableShape rowShape) {
        VersionedTable<Long, List<Object>> table = delegate.createTable(metadata(identity));
        return new MvccStorageTable(identity, rowShape, table);
    }

    public MvccStorageTable openTable(DelosTableIdentity identity, DelosTableShape rowShape) {
        VersionedTable<Long, List<Object>> table = delegate.openTable(metadata(identity));
        return new MvccStorageTable(identity, rowShape, table);
    }

    VersionedStorageProvider delegate() {
        return delegate;
    }

    static VersionedTableMetadata metadata(DelosTableIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return new VersionedTableMetadata(identity.schemaName(), identity.tableName());
    }

    private static DelosTableIdentity tableIdentity(VersionedTableMetadata metadata) {
        return new DelosTableIdentity(metadata.schemaName(), metadata.tableName());
    }
}
