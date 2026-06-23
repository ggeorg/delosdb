package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Phase F7 proof: a prepared Derby UPDATE equality reaches the native
 * ResultSetFactory DML branch, carries DelosRowIdentity from the native
 * scan source, and updates through EngineMvccTableAccess.update(...)
 * without calling the transitional SQL bridge for the UPDATE proof path.
 */
public final class StoragePhaseF7NativeUpdateEqualitySmoke {
    private static final String DATABASE_PATH = "storage-phase-f7-native-update-equality-db";
    private static final String TABLE_NAME = "F7_NATIVE_UPDATE_EQ";

    private StoragePhaseF7NativeUpdateEqualitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createDerbyCatalogTableAndProviderTable();
            proveNativeUpdateEqualityAndReadBack();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f7_native_update_equality: PASS");
    }

    private static void createDerbyCatalogTableAndProviderTable() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE " + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc")) {
                statement.executeUpdate();
            }
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "F7 catalog/provider setup must use Derby prepare/constant-action path, not bridge: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }

        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
    }

    private static void proveNativeUpdateEqualityAndReadBack() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY, "true");

            insert(connection, 1, "alpha");
            insert(connection, 2, "bravo");
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "F7 seed INSERTs should use the F5 native INSERT path, not bridge: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());

            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE APP." + TABLE_NAME + " SET value = ? WHERE id = ?")) {
                update.setString(1, "beta");
                update.setInt(2, 1);
                require(update.executeUpdate() == 1,
                        "Expected F7 native MVCC UPDATE equality to affect one row");
            }

            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "F7 prepared UPDATE proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());

            try (PreparedStatement updated = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                updated.setInt(1, 1);
                try (ResultSet rows = updated.executeQuery()) {
                    require(rows.next(), "Expected F7 native UPDATE equality to keep id=1 visible");
                    require(rows.getInt(1) == 1, "Unexpected updated id after F7 UPDATE");
                    require("beta".equals(rows.getString(2)), "Expected F7 native UPDATE equality to set id=1 to beta");
                    require(!rows.next(), "Expected exactly one updated row after F7 UPDATE");
                }
            }

            try (PreparedStatement survivor = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                survivor.setInt(1, 2);
                try (ResultSet rows = survivor.executeQuery()) {
                    require(rows.next(), "Expected F7 native UPDATE equality to leave id=2 visible");
                    require(rows.getInt(1) == 2, "Unexpected surviving id after F7 UPDATE");
                    require("bravo".equals(rows.getString(2)), "Unexpected surviving value after F7 UPDATE");
                    require(!rows.next(), "Expected exactly one surviving row after F7 UPDATE");
                }
            }

            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "F7 readback SELECTs must also stay off VersionedStorageSqlBridge.tryExecute(...): "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }
    }

    private static void insert(Connection connection, int id, String value) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)")) {
            insert.setInt(1, id);
            insert.setString(2, value);
            require(insert.executeUpdate() == 1, "Expected F7 seed INSERT to affect one row");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
