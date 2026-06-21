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
 * Phase C10 proof: a planned/resolved table operation can drive an MVCC update
 * through the engine-side execution bridge without entering the JDBC SQL-regex
 * bridge.
 */
public final class StoragePhaseC10UpdateProofSmoke {
    private StoragePhaseC10UpdateProofSmoke() {
    }

    public static void main(String[] args) {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedStorageProviderRegistry registry = VersionedStorageProviderRegistry.empty();
        registry.registerEnabled(provider, "phase-c10-update-proof");

        VersionedStorageExecutionBridge lookupBridge = new VersionedStorageExecutionBridge(registry.resolver());
        VersionedStorageExecutionBridge operationBridge = VersionedStorageExecutionBridge.resolvedTableOperations();
        VersionedTableMetadata metadata = new VersionedTableMetadata("APP", "C10_UPDATE_PROOF");
        VersionedTable<Long, String> table = lookupBridge.createTable(DelosMvccStorageProvider.PROVIDER_NAME, metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        insertAndCommit(operationBridge, table, coordinator, 1L, "update-commit-before");
        new PlannedUpdate<>(metadata, 1L, "update-commit-after").executeAndCommit(operationBridge, table, coordinator);

        TxContext committedUpdateReader = coordinator.begin();
        try {
            requireValue(
                    operationBridge.read(table, 1L, committedUpdateReader.currentView()),
                    "update-commit-after",
                    "planned committed update must expose the replacement value");
            requireNotValue(
                    operationBridge.read(table, 1L, committedUpdateReader.currentView()),
                    "update-commit-before",
                    "planned committed update must hide the old value");
        } finally {
            coordinator.abort(committedUpdateReader);
        }

        insertAndCommit(operationBridge, table, coordinator, 2L, "update-abort-before");
        new PlannedUpdate<>(metadata, 2L, "update-abort-after").executeAndAbort(operationBridge, table, coordinator);

        TxContext abortedUpdateReader = coordinator.begin();
        try {
            requireValue(
                    operationBridge.read(table, 2L, abortedUpdateReader.currentView()),
                    "update-abort-before",
                    "planned aborted update must leave the old value visible");
            requireNotValue(
                    operationBridge.read(table, 2L, abortedUpdateReader.currentView()),
                    "update-abort-after",
                    "planned aborted update must not expose the replacement value");
        } finally {
            coordinator.abort(abortedUpdateReader);
        }

        System.out.println("storage_phase_c10_update_proof provider="
                + DelosMvccStorageProvider.PROVIDER_NAME
                + " table=" + metadata.qualifiedName());
        System.out.println("DelosDB Phase C10 planned update smoke test passed.");
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

    private static void requireNotValue(Optional<String> actual, String rejected, String message) {
        if (actual.isPresent() && rejected.equals(actual.get())) {
            throw new IllegalStateException(message + ": rejected=" + rejected + " actual=" + actual);
        }
    }

    private record PlannedUpdate<K, V>(VersionedTableMetadata metadata, K key, V value) {
        private PlannedUpdate {
            metadata = Objects.requireNonNull(metadata, "metadata");
            key = Objects.requireNonNull(key, "key");
            value = Objects.requireNonNull(value, "value");
        }

        private void execute(VersionedStorageExecutionBridge bridge, VersionedTable<K, V> table, TxContext transaction) {
            bridge.update(table, key, value, Objects.requireNonNull(transaction, "transaction"));
        }

        private void executeAndCommit(
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

        private void executeAndAbort(
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
