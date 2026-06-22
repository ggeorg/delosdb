package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Phase F8 proof: the transitional VersionedStorageSqlBridge pre-parse hook is
 * bypassed globally in native mode, while the legacy bridge remains available
 * only through the explicit compatibility property used by transitional smokes.
 */
public final class StoragePhaseF8BridgeBypassSmoke {
    private static final String DATABASE_PATH = "storage-phase-f8-bridge-bypass-db";

    private StoragePhaseF8BridgeBypassSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveNativeModeBypassesEmbedStatementBridge();
            proveCompatibilityModeCanStillUseBridge();
        } finally {
            System.clearProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY);
            System.clearProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY);
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f8_bridge_bypass: PASS");
    }

    private static void proveNativeModeBypassesEmbedStatementBridge() throws Exception {
        System.setProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY, "true");
        System.setProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY, "true");
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE F8_NATIVE_BYPASS (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected Derby native CREATE TABLE USING delos_mvcc to run with bridge bypassed");
        }

        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "Native mode must bypass EmbedStatement -> VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
    }

    private static void proveCompatibilityModeCanStillUseBridge() throws Exception {
        System.clearProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY);
        System.setProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY, "true");
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP.F8_COMPAT_BRIDGE (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected explicit compatibility bridge mode to preserve the old bridge route");
        }

        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isPresent(),
                "Compatibility mode must keep the transitional bridge explicitly available");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
