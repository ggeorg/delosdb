package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Phase G0 proof: native Delos table scans correctly preserve Derby's
 * qualifier layout: leading AND equality qualifiers are accumulated as filters,
 * while OR-shaped predicates remain explicitly unsupported until G adds broader
 * predicate support.
 */
public final class StoragePhaseG0NativeQualifierConjunctionSmoke {
    private static final String DATABASE_PATH = "storage-phase-g0-native-qualifier-conjunction-db";
    private static final String TABLE_NAME = "G0_NATIVE_QUALIFIER_CONJUNCTION";

    private StoragePhaseG0NativeQualifierConjunctionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createDerbyCatalogTableAndSeedMvccRows();
            proveNativeSelectAndConjunctionFiltering();
            proveNativeOrGroupStillRejected();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_g0_native_qualifier_conjunction: PASS");
    }

    private static void createDerbyCatalogTableAndSeedMvccRows() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE " + TABLE_NAME + " (id INT, kind VARCHAR(16), value VARCHAR(32)) USING delos_mvcc")) {
                statement.executeUpdate();
            }
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G0 catalog setup must use Derby prepare/constant-action path, not bridge: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }

        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            insert(connection, 1, "target", "alpha", "matching row");
            insert(connection, 1, "other", "wrong-kind", "same-id leftover-filter row");
            insert(connection, 2, "target", "wrong-id", "different-id row");
        }
        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "G0 native INSERT setup must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
    }

    private static void insert(Connection connection, int id, String kind, String value, String label) throws Exception {
        require(SmokeUtils.executePreparedUpdate(connection,
                        "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?, ?)", id, kind, value) == 1,
                "Expected native MVCC row setup for " + label);
    }

    private static void proveNativeSelectAndConjunctionFiltering() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ? AND kind = ?")) {
                statement.setInt(1, 1);
                statement.setString(2, "target");
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next(), "Expected native MVCC SELECT equality conjunction to return one row");
                    require(rows.getInt(1) == 1, "Unexpected id from native MVCC equality conjunction");
                    require("target".equals(rows.getString(2)), "Unexpected kind from native MVCC equality conjunction");
                    require("alpha".equals(rows.getString(3)), "Unexpected value from native MVCC equality conjunction");
                    require(!rows.next(), "Expected second equality predicate to filter same-id non-matching rows");
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ? AND kind = ?")) {
                statement.setInt(1, 1);
                statement.setString(2, "missing");
                try (ResultSet rows = statement.executeQuery()) {
                    require(!rows.next(), "Expected native MVCC equality conjunction to enforce leftover predicate");
                }
            }

            require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                    "Expected GenericResultSetFactory probe to observe the native G0 table scan");
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G0 prepared SELECT conjunction proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }
    }

    private static void proveNativeOrGroupStillRejected() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ? OR kind = ?")) {
                statement.setInt(1, 1);
                statement.setString(2, "target");
                statement.executeQuery().close();
                throw new IllegalStateException("Expected native MVCC OR qualifier group to remain unsupported");
            } catch (SQLException expected) {
                require(exceptionChainContains(expected, "OR qualifier groups")
                                || exceptionChainContains(expected, "requires an equality qualifier")
                                || exceptionChainContains(expected, "only supports non-negated equality qualifiers"),
                        "Expected native OR-shaped predicate to remain unsupported, but was: " + expected);
            }

            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G0 OR rejection proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }
    }

    private static boolean exceptionChainContains(Throwable failure, String expected) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains(expected)) {
                return true;
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
