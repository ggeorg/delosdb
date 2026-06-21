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
 * Phase C5 proof: a planned table identity plus one row mutation can drive an
 * MVCC insert through the engine-side execution bridge without entering the
 * JDBC SQL-regex bridge.
 */
public final class StoragePhaseC5InsertProofSmoke {
    private StoragePhaseC5InsertProofSmoke() {
    }

    public static void main(String[] args) {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedStorageProviderRegistry registry = VersionedStorageProviderRegistry.empty();
        registry.registerEnabled(provider, "phase-c5-insert-proof");

        VersionedStorageExecutionBridge bridge = new VersionedStorageExecutionBridge(registry.resolver());
        VersionedTableMetadata metadata = new VersionedTableMetadata("APP", "C5_INSERT_PROOF");
        VersionedTable<Integer, String> table = bridge.createTable(DelosMvccStorageProvider.PROVIDER_NAME, metadata);
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        PlannedInsert<Integer, String> committedInsert = new PlannedInsert<>(
                DelosMvccStorageProvider.PROVIDER_NAME,
                metadata,
                1,
                "committed-alpha");
        committedInsert.executeAndCommit(bridge, table, coordinator);

        TxContext committedReader = coordinator.begin();
        try {
            requireValue(
                    bridge.read(table, 1, committedReader.currentView()),
                    "committed-alpha",
                    "planned committed insert must be visible");
        } finally {
            coordinator.abort(committedReader);
        }

        PlannedInsert<Integer, String> rolledBackInsert = new PlannedInsert<>(
                DelosMvccStorageProvider.PROVIDER_NAME,
                metadata,
                2,
                "rolled-back-beta");
        rolledBackInsert.executeAndAbort(bridge, table, coordinator);

        TxContext rollbackReader = coordinator.begin();
        try {
            requireMissing(
                    bridge.read(table, 2, rollbackReader.currentView()),
                    "planned aborted insert must not be visible");
        } finally {
            coordinator.abort(rollbackReader);
        }

        requireMissingProviderIsRejected(bridge, metadata);

        System.out.println("storage_phase_c5_insert_proof provider=" + committedInsert.providerName()
                + " table=" + committedInsert.metadata().qualifiedName());
        System.out.println("DelosDB Phase C5 planned insert smoke test passed.");
    }

    private static void requireMissingProviderIsRejected(
            VersionedStorageExecutionBridge bridge,
            VersionedTableMetadata metadata) {
        PlannedInsert<Integer, String> missingProviderPlan = new PlannedInsert<>(
                "missing_provider",
                metadata,
                3,
                "missing-provider-gamma");
        try {
            missingProviderPlan.open(bridge);
            throw new AssertionError("Missing planned-insert provider unexpectedly resolved");
        } catch (RuntimeException expected) {
            String message = expected.getMessage();
            if (message == null || !message.contains("missing_provider")) {
                throw new AssertionError("Unexpected missing-provider diagnostic: " + message, expected);
            }
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

    private record PlannedInsert<K, V>(String providerName, VersionedTableMetadata metadata, K key, V value) {
        private PlannedInsert {
            providerName = requireProviderName(providerName);
            metadata = Objects.requireNonNull(metadata, "metadata");
            key = Objects.requireNonNull(key, "key");
            value = Objects.requireNonNull(value, "value");
        }

        private VersionedTable<K, V> open(VersionedStorageExecutionBridge bridge) {
            return Objects.requireNonNull(bridge, "bridge").openTable(providerName, metadata);
        }

        private void execute(VersionedStorageExecutionBridge bridge, VersionedTable<K, V> table, TxContext transaction) {
            bridge.insert(table, key, value, Objects.requireNonNull(transaction, "transaction"));
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

        private static String requireProviderName(String value) {
            String trimmed = Objects.requireNonNull(value, "providerName").trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("providerName must not be blank");
            }
            return trimmed;
        }
    }
}
