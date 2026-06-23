package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase G6 proof: supported delos_mvcc SQL no longer depends on the
 * VersionedStorageSqlBridge pre-parse fallback. Plain Statement CREATE TABLE
 * and prepared native INSERT/SELECT flow through Derby-native metadata and
 * ResultSetFactory seams, while the old compatibility property cannot re-enable
 * EmbedStatement interception.
 */
public final class StoragePhaseG6BridgeRetirementSmoke {
    private static final String DATABASE_PATH = "storage-phase-g6-bridge-retirement-db";
    private static final String TABLE_NAME = "G6_BRIDGE_RETIREMENT";

    private StoragePhaseG6BridgeRetirementSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveBridgeInterceptionRetired();
            proveSupportedNativeSqlDoesNotNeedBridgeSetup();
        } finally {
            System.clearProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY);
            System.clearProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_g6_bridge_retirement: PASS");
    }

    private static void proveBridgeInterceptionRetired() {
        System.clearProperty(VersionedStorageSqlBridge.NATIVE_EXECUTION_MODE_PROPERTY);
        System.clearProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY);
        require(!VersionedStorageSqlBridge.isInterceptionEnabled(),
                "G6 requires VersionedStorageSqlBridge interception to be disabled by default");

        System.setProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY, "true");
        require(!VersionedStorageSqlBridge.isInterceptionEnabled(),
                "G6 must not allow compatibility opt-in to re-enable SQL fallback");
    }

    private static void proveSupportedNativeSqlDoesNotNeedBridgeSetup() throws Exception {
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
        System.setProperty(VersionedStorageSqlBridge.BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected native Derby CREATE TABLE to register provider-owned delos_mvcc storage");
        }

        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "G6 CREATE TABLE must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 1, "alpha") == 1,
                    "Expected native INSERT to use provider table registered by native CREATE TABLE");

            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                select.setInt(1, 1);
                try (ResultSet rows = select.executeQuery()) {
                    require(rows.next(), "Expected native SELECT to read the row inserted without bridge setup");
                    require(rows.getInt(1) == 1, "Unexpected id from G6 native SELECT");
                    require("alpha".equals(rows.getString(2)), "Unexpected value from G6 native SELECT");
                    require(!rows.next(), "Expected exactly one row from G6 native SELECT");
                }
            }
        }

        require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                "Expected ResultSetFactory provider lookup during G6 native SELECT");
        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "G6 native INSERT/SELECT must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
