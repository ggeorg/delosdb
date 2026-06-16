package io.github.ggeorg.delosdb.storage.mvcc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * Experimental MVCC storage provider.
 *
 * <p>The default constructor remains in-memory for fast model and SPI tests.
 * {@link #open(Path)} enables the Phase 6 provider-local append-only recovery
 * log. This is not Derby WAL and does not alter Derby-compatible heap storage.</p>
 */
public final class DelosMvccStorageProvider implements VersionedStorageProvider {
    public static final String PROVIDER_NAME = "delos_mvcc";

    private final Map<VersionedTableMetadata, DelosMvccTable<?, ?>> tables = new LinkedHashMap<>();
    private final DelosMvccStorageLog storageLog;
    private final DelosMvccTransactionCoordinator transactionCoordinator;
    private final VersionedStorageCapabilities capabilities;
    private boolean recovering;

    /** Creates the default in-memory prototype provider used by ServiceLoader. */
    public DelosMvccStorageProvider() {
        this(DelosMvccStorageLog.disabled(), false);
    }

    /** Opens a provider instance backed by the provider-local append-only log. */
    public static DelosMvccStorageProvider open(Path storageDirectory) {
        return new DelosMvccStorageProvider(DelosMvccStorageLog.open(storageDirectory), true);
    }

    private DelosMvccStorageProvider(DelosMvccStorageLog storageLog, boolean recover) {
        this.storageLog = Objects.requireNonNull(storageLog, "storageLog");
        this.transactionCoordinator = new DelosMvccTransactionCoordinator(storageLog, this::isRecovering);
        Set<String> capabilityValues = new LinkedHashSet<>();
        capabilityValues.add(VersionedStorageCapabilities.SNAPSHOT_VISIBILITY);
        capabilityValues.add(VersionedStorageCapabilities.TABLE_SCAN);
        capabilityValues.add(VersionedStorageCapabilities.MANUAL_CLEANUP);
        capabilityValues.add(VersionedStorageCapabilities.PROVIDER_OWNED_INDEXES);
        capabilityValues.add(VersionedStorageCapabilities.IN_MEMORY_PROTOTYPE);
        if (storageLog.isEnabled()) {
            capabilityValues.add(VersionedStorageCapabilities.APPEND_ONLY_RECOVERY_LOG);
        }
        this.capabilities = new VersionedStorageCapabilities(capabilityValues);
        if (recover) {
            recoverFromLog();
        }
    }

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
        DelosMvccTable<K, V> table = createTableInternal(metadata);
        if (!recovering) {
            storageLog.appendCreateTable(metadata);
        }
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

    private synchronized <K, V> DelosMvccTable<K, V> createTableInternal(VersionedTableMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (tables.containsKey(metadata)) {
            throw new IllegalStateException("versioned table already exists: " + metadata.qualifiedName());
        }
        DelosMvccTable<K, V> table = new DelosMvccTable<>(metadata, new MvccTable<>(), storageLog, this::isRecovering);
        tables.put(metadata, table);
        return table;
    }

    private boolean isRecovering() {
        return recovering;
    }

    private void recoverFromLog() {
        DelosMvccStorageLog.RecoveryImage image = storageLog.recover();
        recovering = true;
        try {
            for (VersionedTableMetadata metadata : image.tables()) {
                if (!tables.containsKey(metadata)) {
                    createTableInternal(metadata);
                }
            }
            for (DelosMvccStorageLog.CommittedTransaction transaction : image.committedTransactions()) {
                replayCommittedTransaction(transaction);
            }
        } finally {
            recovering = false;
        }
    }

    private void replayCommittedTransaction(DelosMvccStorageLog.CommittedTransaction transaction) {
        DelosMvccTxContext context = transactionCoordinator.begin();
        try {
            for (DelosMvccStorageLog.RecoveredChange change : transaction.changes()) {
                VersionedTable<Long, List<Object>> table = openTable(change.metadata());
                if (change.isInsert()) {
                    table.insert(change.key(), change.values(), context);
                } else if (change.isUpdate()) {
                    table.update(change.key(), change.values(), context);
                } else if (change.isDelete()) {
                    table.delete(change.key(), context);
                } else {
                    throw new IllegalStateException("Unsupported delos_mvcc recovered operation: " + change.operation());
                }
            }
            transactionCoordinator.commit(context);
        } catch (RuntimeException e) {
            transactionCoordinator.abort(context);
            throw e;
        }
    }
}
