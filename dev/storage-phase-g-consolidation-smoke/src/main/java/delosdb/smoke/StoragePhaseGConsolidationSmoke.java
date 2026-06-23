package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase G consolidation proof: after G6, the supported delos_mvcc native path
 * must stay independent of the old SQL bridge fallback.  This smoke combines a
 * small executable native lifecycle check with source/build cleanup guards for
 * the soft-G6 compatibility-mode archaeology.
 */
public final class StoragePhaseGConsolidationSmoke {
    private static final String DATABASE_PATH = "storage-phase-g-consolidation-db";
    private static final String TABLE_NAME = "G_CONSOLIDATION";

    private StoragePhaseGConsolidationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveBridgeInterceptionCannotBeEnabled();
            proveNoCompatibilityModeWiringInGradle();
            proveNoSoftG6Archaeology();
            proveNativeLifecycleWithoutBridgeFallback();
        } finally {
            clearProofProperties();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_g_consolidation: PASS");
    }

    private static void proveBridgeInterceptionCannotBeEnabled() {
        clearProofProperties();
        require(!VersionedStorageSqlBridge.isInterceptionEnabled(),
                "Bridge interception must be disabled by default after G6");

        System.setProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY, "true");
        require(!VersionedStorageSqlBridge.isInterceptionEnabled(),
                "Compatibility property must not re-enable bridge interception after G6");

        System.setProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY, "true");
        require(!VersionedStorageSqlBridge.isInterceptionEnabled(),
                "Native-mode property must not change bridge interception after G6");
    }

    private static void proveNoCompatibilityModeWiringInGradle() throws Exception {
        List<Path> gradleFiles = List.of(
                Path.of("gradle/storage-phase-f-native-execution.gradle"),
                Path.of("gradle/storage-phase-g-native-predicates.gradle"),
                Path.of("gradle/delosdb-permanent-storage-guards.gradle"));
        List<String> violations = new ArrayList<>();
        for (Path gradleFile : gradleFiles) {
            if (!Files.exists(gradleFile)) {
                violations.add("missing expected Gradle file: " + gradleFile);
                continue;
            }
            String text = Files.readString(gradleFile);
            if (text.contains("delosdb.storage.sqlBridge.compatibility")
                    || text.contains("BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY")
                    || text.contains("BridgeReduction")
                    || text.contains("bridge-reduction")) {
                violations.add("stale bridge-compatibility wiring in " + gradleFile);
            }
        }
        require(violations.isEmpty(), String.join("; ", violations));
    }

    private static void proveNoSoftG6Archaeology() {
        List<Path> stalePaths = List.of(
                Path.of("dev/storage-phase-g6-bridge-reduction-smoke"),
                Path.of("storage-phase-g6-bridge-reduction-db"));
        List<String> present = new ArrayList<>();
        for (Path stalePath : stalePaths) {
            if (Files.exists(stalePath)) {
                present.add(stalePath.toString());
            }
        }
        require(present.isEmpty(), "stale soft-G6 bridge-reduction artifacts remain: " + present);
    }

    private static void proveNativeLifecycleWithoutBridgeFallback() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc to run without bridge fallback");
        }

        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "CREATE TABLE must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 7, "seven") == 1,
                    "Expected native INSERT to use provider storage registered by Derby CREATE TABLE");

            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                select.setInt(1, 7);
                try (ResultSet rows = select.executeQuery()) {
                    require(rows.next(), "Expected one native row during G consolidation");
                    require(rows.getInt(1) == 7, "Unexpected id during G consolidation");
                    require("seven".equals(rows.getString(2)), "Unexpected value during G consolidation");
                    require(!rows.next(), "Expected exactly one native row during G consolidation");
                }
            }
        }

        require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                "Expected ResultSetFactory provider lookup during G consolidation SELECT");
        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "Native INSERT/SELECT must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
    }

    private static void clearProofProperties() {
        System.clearProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY);
        System.clearProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
