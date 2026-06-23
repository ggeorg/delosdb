package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Phase F8/G6 proof: the transitional VersionedStorageSqlBridge pre-parse hook
 * is retired as an automatic SQL fallback. Native Derby execution owns
 * CREATE TABLE ... USING delos_mvcc even for plain Statement execution, and no
 * compatibility property may re-enable EmbedStatement interception.
 */
public final class StoragePhaseF8BridgeBypassSmoke {
    private static final String DATABASE_PATH = "storage-phase-f8-bridge-bypass-db";

    private StoragePhaseF8BridgeBypassSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveBridgeInterceptionCannotBeEnabledByProperty();
            provePlainStatementCreateTableUsesNativeDerbyPath();
        } finally {
            System.clearProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY);
            System.clearProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY);
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f8_bridge_bypass: PASS");
    }

    private static void proveBridgeInterceptionCannotBeEnabledByProperty() {
        System.clearProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY);
        System.clearProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY);
        require(!VersionedStorageSqlBridge.isInterceptionEnabled(),
                "G6 requires EmbedStatement bridge interception to be disabled by default");

        System.setProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY, "true");
        require(!VersionedStorageSqlBridge.isInterceptionEnabled(),
                "G6 must not allow the old compatibility property to re-enable bridge fallback");

        System.setProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY, "true");
        require(!VersionedStorageSqlBridge.isInterceptionEnabled(),
                "Native mode must also leave bridge interception disabled");
    }

    private static void provePlainStatementCreateTableUsesNativeDerbyPath() throws Exception {
        System.clearProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY);
        System.setProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY, "true");
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE F8_NATIVE_BYPASS (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected Derby native CREATE TABLE USING delos_mvcc to run with bridge fallback retired");
        }

        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "Plain Statement CREATE TABLE must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
