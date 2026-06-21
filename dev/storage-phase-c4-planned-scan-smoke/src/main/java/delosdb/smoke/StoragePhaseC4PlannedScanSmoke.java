package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderRegistry;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;

import java.util.List;
import java.util.Objects;

/**
 * Phase C4 proof: a planned table identity can drive an MVCC table scan through
 * the engine-side execution bridge without entering the JDBC SQL-regex bridge.
 */
public final class StoragePhaseC4PlannedScanSmoke {
    private StoragePhaseC4PlannedScanSmoke() {
    }

    public static void main(String[] args) {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedStorageProviderRegistry registry = VersionedStorageProviderRegistry.empty();
        registry.registerEnabled(provider, "phase-c4-planned-scan");

        VersionedStorageExecutionBridge bridge = new VersionedStorageExecutionBridge(registry.resolver());
        PlannedScan<Integer, String> scanPlan = new PlannedScan<>(
                DelosMvccStorageProvider.PROVIDER_NAME,
                new VersionedTableMetadata("APP", "C4_PLANNED_SCAN"));

        VersionedTable<Integer, String> table = bridge.createTable(scanPlan.providerName(), scanPlan.metadata());
        VersionedTransactionCoordinator coordinator = provider.transactionCoordinator();

        TxContext writer = coordinator.begin();
        bridge.insert(table, 1, "alpha", writer);
        bridge.insert(table, 2, "beta", writer);
        coordinator.commit(writer);

        TxContext reader = coordinator.begin();
        try {
            requireRows(
                    scanPlan.execute(bridge, reader.currentView()),
                    List.of(new VersionedRow<>(1, "alpha"), new VersionedRow<>(2, "beta")),
                    "planned MVCC scan rows");
        } finally {
            coordinator.abort(reader);
        }

        requireMissingProviderIsRejected(bridge, scanPlan.metadata());

        System.out.println("storage_phase_c4_planned_scan provider=" + scanPlan.providerName()
                + " table=" + scanPlan.metadata().qualifiedName());
        System.out.println("DelosDB Phase C4 planned scan smoke test passed.");
    }

    private static void requireMissingProviderIsRejected(
            VersionedStorageExecutionBridge bridge,
            VersionedTableMetadata metadata) {
        PlannedScan<Integer, String> missingProviderPlan = new PlannedScan<>("missing_provider", metadata);
        try {
            missingProviderPlan.open(bridge);
            throw new AssertionError("Missing planned-scan provider unexpectedly resolved");
        } catch (RuntimeException expected) {
            String message = expected.getMessage();
            if (message == null || !message.contains("missing_provider")) {
                throw new AssertionError("Unexpected missing-provider diagnostic: " + message, expected);
            }
        }
    }

    private static <K, V> void requireRows(
            List<VersionedRow<K, V>> actual,
            List<VersionedRow<K, V>> expected,
            String message) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private record PlannedScan<K, V>(String providerName, VersionedTableMetadata metadata) {
        private PlannedScan {
            providerName = requireProviderName(providerName);
            metadata = Objects.requireNonNull(metadata, "metadata");
        }

        private VersionedTable<K, V> open(VersionedStorageExecutionBridge bridge) {
            return Objects.requireNonNull(bridge, "bridge").openTable(providerName, metadata);
        }

        private List<VersionedRow<K, V>> execute(VersionedStorageExecutionBridge bridge, TxView view) {
            return bridge.scanAll(open(bridge), Objects.requireNonNull(view, "view"));
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
