package io.github.ggeorg.delosdb.storage.mvcc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageCapabilities;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedStorageProvider;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.storage.mvcc.durable.PageBackedMvccTable;

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
    public static final String DATABASE_STORAGE_DIRECTORY_NAME = "delos_mvcc";
    public static final String TRANSACTION_STATUS_FILE_NAME = "delos-mvcc-tx-status.log";

    private final DelosMvccStorageLog storageLog;
    private final MvccTransactionStatusStore transactionStatusStore;
    private final Path pageBackedStorageDirectory;
    private final DelosMvccTransactionCoordinator transactionCoordinator;
    private final VersionedStorageCapabilities capabilities;
    private boolean recovering;

    /** Creates the default in-memory prototype provider used by ServiceLoader. */
    public DelosMvccStorageProvider() {
        this(DelosMvccStorageLog.disabled(), MvccTransactionStatusStore.disabled(), null, false);
    }

    /** Opens a provider instance backed by the provider-local append-only log. */
    public static DelosMvccStorageProvider open(Path storageDirectory) {
        return new DelosMvccStorageProvider(
                DelosMvccStorageLog.open(storageDirectory),
                MvccTransactionStatusStore.open(transactionStatusPath(storageDirectory)),
                null,
                true);
    }

    @Override
    public VersionedStorageProvider openForDatabase(Path databaseDirectory) {
        Objects.requireNonNull(databaseDirectory, "databaseDirectory");
        return open(databaseStorageDirectory(databaseDirectory));
    }

    public static Path databaseStorageDirectory(Path databaseDirectory) {
        return Objects.requireNonNull(databaseDirectory, "databaseDirectory")
                .resolve(DATABASE_STORAGE_DIRECTORY_NAME);
    }

    public static Path transactionStatusPath(Path storageDirectory) {
        return Objects.requireNonNull(storageDirectory, "storageDirectory")
                .resolve(TRANSACTION_STATUS_FILE_NAME);
    }

    /**
     * Opens a provider instance backed by the Phase A page-file table store.
     *
     * <p>This path is deliberately separate from {@link #open(Path)} so the
     * existing provider-local recovery-log proofs keep their original contract.</p>
     */
    public static DelosMvccStorageProvider openPageBacked(Path storageDirectory) {
        Objects.requireNonNull(storageDirectory, "storageDirectory");
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create delos_mvcc page-backed storage directory: "
                    + storageDirectory, e);
        }
        return new DelosMvccStorageProvider(
                DelosMvccStorageLog.disabled(),
                MvccTransactionStatusStore.open(transactionStatusPath(storageDirectory)),
                storageDirectory,
                false);
    }

    private DelosMvccStorageProvider(
            DelosMvccStorageLog storageLog,
            MvccTransactionStatusStore transactionStatusStore,
            Path pageBackedStorageDirectory,
            boolean recover) {
        this.storageLog = Objects.requireNonNull(storageLog, "storageLog");
        this.transactionStatusStore = Objects.requireNonNull(transactionStatusStore, "transactionStatusStore");
        this.pageBackedStorageDirectory = pageBackedStorageDirectory;
        this.transactionCoordinator = new DelosMvccTransactionCoordinator(
                storageLog,
                transactionStatusStore,
                this::isRecovering,
                new DelosMvccTransactionCoordinator.TransactionCompletionListener() {
                    @Override
                    public void committed(long transactionId, MvccCommitSequence commitSequence) {
                        completeDurableTransaction(transactionId, commitSequence, true);
                    }

                    @Override
                    public void aborted(long transactionId) {
                        completeDurableTransaction(transactionId, MvccCommitSequence.NONE, false);
                    }
                });
        Set<String> capabilityValues = new LinkedHashSet<>();
        capabilityValues.add(VersionedStorageCapabilities.SNAPSHOT_VISIBILITY);
        capabilityValues.add(VersionedStorageCapabilities.TABLE_SCAN);
        capabilityValues.add(VersionedStorageCapabilities.MANUAL_CLEANUP);
        capabilityValues.add(VersionedStorageCapabilities.PROVIDER_OWNED_INDEXES);
        capabilityValues.add(VersionedStorageCapabilities.ORDERED_PROVIDER_OWNED_INDEXES);
        capabilityValues.add(VersionedStorageCapabilities.IN_MEMORY_PROTOTYPE);
        if (storageLog.isEnabled()) {
            capabilityValues.add(VersionedStorageCapabilities.APPEND_ONLY_RECOVERY_LOG);
        }
        if (transactionStatusStore.isEnabled()) {
            capabilityValues.add("persistent-mvcc-transaction-status");
        }
        if (pageBackedStorageDirectory != null) {
            capabilityValues.add(VersionedStorageCapabilities.APPEND_ONLY_RECOVERY_LOG);
            capabilityValues.add("page-backed-table-store");
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

    public synchronized MvccCleanupResult cleanup() {
        MvccCleanupResult result = new MvccCleanupResult(0);
        for (DelosMvccTable<?, ?> table : tables.values()) {
            result = result.plus(transactionCoordinator.cleanup(table));
        }
        return result;
    }

    /**
     * Rewrites the provider-local recovery log into a compact committed image.
     *
     * <p>This is a prototype checkpoint, not Derby WAL. It is intentionally
     * conservative: compaction is refused while a provider transaction is
     * active, because old snapshots may still need pre-cleanup versions that a
     * compact image would not preserve.</p>
     */
    public synchronized void checkpoint() {
        if (!storageLog.isEnabled()) {
            return;
        }
        if (transactionCoordinator.hasActiveTransactions()) {
            throw new IllegalStateException("Cannot checkpoint delos_mvcc while provider transactions are active");
        }

        DelosMvccTxContext checkpointReader = transactionCoordinator.begin();
        List<DelosMvccStorageLog.CheckpointRow> rows = new ArrayList<>();
        try {
            for (DelosMvccTable<?, ?> table : tables.values()) {
                rows.addAll(table.checkpointRows(checkpointReader.currentView()));
            }
        } finally {
            transactionCoordinator.abort(checkpointReader);
        }
        storageLog.rewriteCheckpoint(listTables(), rows);
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
        DelosMvccTable<K, V> table = new DelosMvccTable<>(
                metadata,
                new MvccTable<>(),
                storageLog,
                this::isRecovering,
                openPageBackedTableIfEnabled(metadata));
        table.hydrateFromDurable(transactionCoordinator);
        tables.put(metadata, table);
        return table;
    }

    private PageBackedMvccTable openPageBackedTableIfEnabled(VersionedTableMetadata metadata) {
        if (pageBackedStorageDirectory == null) {
            return null;
        }
        try {
            Path pageFile = pageBackedStorageDirectory.resolve(pageFileName(metadata));
            return PageBackedMvccTable.open(pageFile, pageMutationLogFile(pageFile));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not open page-backed delos_mvcc table "
                    + metadata.qualifiedName(), e);
        }
    }

    private static String pageFileName(VersionedTableMetadata metadata) {
        String name = (metadata.schemaName() + "_" + metadata.tableName()).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_");
        return name + ".dmvcc";
    }

    private static Path pageMutationLogFile(Path pageFile) {
        return pageFile.resolveSibling(pageFile.getFileName() + ".log");
    }

    private synchronized void completeDurableTransaction(
            long transactionId,
            MvccCommitSequence commitSequence,
            boolean commit) {
        for (DelosMvccTable<?, ?> table : tables.values()) {
            if (commit) {
                table.durableCommit(transactionId, commitSequence);
            } else {
                table.durableAbort(transactionId);
            }
        }
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
