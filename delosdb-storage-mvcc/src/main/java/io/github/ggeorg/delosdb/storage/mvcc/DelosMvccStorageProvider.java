package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;

/**
 * Experimental in-memory MVCC storage provider.
 *
 * <p>This is a provider boundary proof, not a SQL-executable storage engine.
 * It keeps the MVCC model outside {@code delosdb-engine} so Derby-compatible
 * heap storage remains the default compatibility path.</p>
 */
public final class DelosMvccStorageProvider implements VersionedStorageProvider {
    public static final String PROVIDER_NAME = "delos_mvcc";

    private final Map<VersionedTableMetadata, DelosMvccTable<?, ?>> tables = new LinkedHashMap<>();
    private final DelosMvccTransactionCoordinator transactionCoordinator = new DelosMvccTransactionCoordinator();
    private final VersionedStorageCapabilities capabilities = new VersionedStorageCapabilities(Set.of(
            VersionedStorageCapabilities.SNAPSHOT_VISIBILITY,
            VersionedStorageCapabilities.TABLE_SCAN,
            VersionedStorageCapabilities.MANUAL_CLEANUP,
            VersionedStorageCapabilities.IN_MEMORY_PROTOTYPE));

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public VersionedStorageCapabilities capabilities() {
        return capabilities;
    }

    @Override
    public VersionedTransactionCoordinator transactionCoordinator() {
        return transactionCoordinator;
    }

    @Override
    public synchronized List<VersionedTableMetadata> listTables() {
        return List.copyOf(new ArrayList<>(tables.keySet()));
    }

    @Override
    public synchronized <K, V> VersionedTable<K, V> createTable(VersionedTableMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (tables.containsKey(metadata)) {
            throw new IllegalStateException("versioned table already exists: " + metadata.qualifiedName());
        }
        DelosMvccTable<K, V> table = new DelosMvccTable<>(metadata, new MvccTable<>());
        tables.put(metadata, table);
        return table;
    }

    @Override
    public synchronized <K, V> VersionedTable<K, V> openTable(VersionedTableMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        DelosMvccTable<?, ?> table = tables.get(metadata);
        if (table == null) {
            throw new IllegalStateException("versioned table does not exist: " + metadata.qualifiedName());
        }
        @SuppressWarnings("unchecked")
        VersionedTable<K, V> typedTable = (VersionedTable<K, V>) table;
        return typedTable;
    }
}
