package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderRegistry;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;

import java.util.Objects;
import java.util.Optional;

/**
 * Phase C8 proof: a planned/resolved table operation can drive an MVCC delete
 * through the engine-side execution bridge without entering the JDBC SQL-regex
 * bridge.
 */
public final class StoragePhaseC8DeleteProofSmoke {
    private StoragePhaseC8DeleteProofSmoke() {
    }

    public static void main(String[] args) {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedStorageProviderRegistry registry = VersionedStorageProviderRegistry.empty();
        registry.registerEnabled(provider, "phase-c8-delete-proof");

        VersionedStorageExecutionBridge lookupBridge = new VersionedStorageExecutionBridge(registry.resolver());
        VersionedStorageExecutionBridge operationBridge = VersionedStorageExecutionBridge.resolvedTableOperations();
        VersionedTableMetadata metadata = new VersionedTableMetadata("APP", "C8_DELETE_PROOF");
        VersionedTable<Long, String> table = lookupBridge.createTable(DelosMvccStorageProvider.PROVIDER_NAME, metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        insertAndCommit(operationBridge, table, coordinator, 1L, "delete-commit-alpha");
        new PlannedDelete<>(metadata, 1L).executeAndCommit(operationBridge, table, coordinator);

        TxContext committedDeleteReader = coordinator.begin();
        try {
            requireMissing(
                    operationBridge.read(table, 1L, committedDeleteReader.currentView()),
                    "planned committed delete must hide the row");
        } finally {
            coordinator.abort(committedDeleteReader);
        }

        insertAndCommit(operationBridge, table, coordinator, 2L, "delete-abort-beta");
        new PlannedDelete<>(metadata, 2L).executeAndAbort(operationBridge, table, coordinator);

        TxContext abortedDeleteReader = coordinator.begin();
        try {
            requireValue(
                    operationBridge.read(table, 2L, abortedDeleteReader.currentView()),
                    "delete-abort-beta",
                    "planned aborted delete must leave the row visible");
        } finally {
            coordinator.abort(abortedDeleteReader);
        }

        System.out.println("storage_phase_c8_delete_proof provider="
                + DelosMvccStorageProvider.PROVIDER_NAME
                + " table=" + metadata.qualifiedName());
        System.out.println("DelosDB Phase C8 planned delete smoke test passed.");
    }

    private static <K, V> void insertAndCommit(
            VersionedStorageExecutionBridge bridge,
            VersionedTable<K, V> table,
            VersionedTransactionCoordinator coordinator,
            K key,
            V value) {
        TxContext transaction = Objects.requireNonNull(coordinator, "coordinator").begin();
        try {
            bridge.insert(table, key, value, transaction);
            coordinator.commit(transaction);
        } catch (RuntimeException | Error failure) {
            coordinator.abort(transaction);
            throw failure;
        }
    }

    private static void requireValue(Optional<String> actual, String expected, String message) {
        if (actual.isEmpty() || !expected.equals(actual.get())) {
            throw new IllegalStateException(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static void requireMissing(Optional<String> actual, String message) {
        if (actual.isPresent()) {
            throw new IllegalStateException(message + ": actual=" + actual);
        }
    }

    private record PlannedDelete<K>(VersionedTableMetadata metadata, K key) {
        private PlannedDelete {
            metadata = Objects.requireNonNull(metadata, "metadata");
            key = Objects.requireNonNull(key, "key");
        }

        private <V> void execute(VersionedStorageExecutionBridge bridge, VersionedTable<K, V> table, TxContext transaction) {
            bridge.delete(table, key, Objects.requireNonNull(transaction, "transaction"));
        }

        private <V> void executeAndCommit(
                VersionedStorageExecutionBridge bridge,
                VersionedTable<K, V> table,
                VersionedTransactionCoordinator coordinator) {
            TxContext transaction = Objects.requireNonNull(coordinator, "coordinator").begin();
            try {
                execute(bridge, table, transaction);
                coordinator.commit(transaction);
            } catch (RuntimeException | Error failure) {
                coordinator.abort(transaction);
                throw failure;
            }
        }

        private <V> void executeAndAbort(
                VersionedStorageExecutionBridge bridge,
                VersionedTable<K, V> table,
                VersionedTransactionCoordinator coordinator) {
            TxContext transaction = Objects.requireNonNull(coordinator, "coordinator").begin();
            try {
                execute(bridge, table, transaction);
            } finally {
                coordinator.abort(transaction);
            }
        }
    }
}
