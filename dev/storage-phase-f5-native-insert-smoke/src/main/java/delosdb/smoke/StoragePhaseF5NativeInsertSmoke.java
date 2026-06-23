package delosdb.smoke;

import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Phase F5 proof: a prepared Derby INSERT reaches the native ResultSetFactory
 * DML branch and mutates the provider-owned MVCC table through
 * EngineMvccTableAccess.insert(...), without calling the transitional SQL
 * bridge for the INSERT proof path.
 */
public final class StoragePhaseF5NativeInsertSmoke {
    private static final String DATABASE_PATH = "storage-phase-f5-native-insert-db";
    private static final String TABLE_NAME = "F5_NATIVE_INSERT";

    private StoragePhaseF5NativeInsertSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createDerbyCatalogTableAndProviderTable();
            proveNativeInsertAndReadBack();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f5_native_insert: PASS");
    }

    private static void createDerbyCatalogTableAndProviderTable() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE " + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc")) {
                statement.executeUpdate();
            }
        }

    }

    private static void proveNativeInsertAndReadBack() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)")) {
                insert.setInt(1, 1);
                insert.setString(2, "alpha");
                require(insert.executeUpdate() == 1,
                        "Expected native MVCC INSERT to report one affected row");
            }


            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                select.setInt(1, 1);
                try (ResultSet rows = select.executeQuery()) {
                    require(rows.next(), "Expected F5 native INSERT row to be visible through native SELECT");
                    require(rows.getInt(1) == 1, "Unexpected id from F5 inserted row");
                    require("alpha".equals(rows.getString(2)), "Unexpected value from F5 inserted row");
                    require(!rows.next(), "Expected exactly one row after F5 native INSERT");
                }
            }

        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
