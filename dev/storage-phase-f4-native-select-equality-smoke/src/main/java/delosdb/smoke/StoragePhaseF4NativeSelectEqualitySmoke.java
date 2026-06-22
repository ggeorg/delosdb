package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Phase F4 proof: a prepared Derby SELECT equality reaches the native
 * ResultSetFactory table-scan branch, translates Derby Qualifier[][] into a
 * DelosPredicate equality, calls EngineMvccTableAccess.scan(...), and
 * materializes provider-owned MVCC rows into Derby ExecRow output.
 */
public final class StoragePhaseF4NativeSelectEqualitySmoke {
    private static final String DATABASE_PATH = "storage-phase-f4-native-select-equality-db";
    private static final String TABLE_NAME = "F4_NATIVE_SELECT_EQ";

    private StoragePhaseF4NativeSelectEqualitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createDerbyCatalogTableAndSeedMvccRows();
            SmokeUtils.shutdown(DATABASE_PATH);
            proveNativeSelectEqualityAfterRestart();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_SKELETON_BRANCH_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f4_native_select_equality: PASS");
    }

    private static void createDerbyCatalogTableAndSeedMvccRows() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            executePrepared(connection,
                    "CREATE TABLE " + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc");
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "F4 catalog setup must use Derby prepare/constant-action path, not bridge: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }

        require(VersionedStorageSqlBridge.tryExecute(
                "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc") != null,
                "Expected provider-owned MVCC table setup to use the existing transitional setup path");
        require(VersionedStorageSqlBridge.tryExecute(
                "INSERT INTO APP." + TABLE_NAME + " VALUES (1, 'alpha')") != null,
                "Expected provider-owned MVCC row setup for id=1");
        require(VersionedStorageSqlBridge.tryExecute(
                "INSERT INTO APP." + TABLE_NAME + " VALUES (2, 'bravo')") != null,
                "Expected provider-owned MVCC row setup for id=2");
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
    }

    private static void proveNativeSelectEqualityAfterRestart() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                statement.setInt(1, 1);
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next(), "Expected native MVCC SELECT equality to return id=1");
                    require(rows.getInt(1) == 1, "Unexpected id from native MVCC SELECT equality");
                    require("alpha".equals(rows.getString(2)),
                            "Unexpected value from native MVCC SELECT equality");
                    require(!rows.next(), "Expected native equality predicate to filter out id=2");
                }
            }

            require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                    "Expected GenericResultSetFactory probe to observe the native table scan");
            require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isPresent(),
                    "Expected non-default provider lookup before native DelosTableScanResultSet execution");
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "F4 prepared SELECT proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }
    }

    private static void executePrepared(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
