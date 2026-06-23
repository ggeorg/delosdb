package delosdb.smoke;

import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Phase F6 proof: a prepared Derby DELETE equality reaches the native
 * ResultSetFactory DML branch, scans provider-owned MVCC rows through the
 * native table-scan predicate machinery, and deletes by DelosRowIdentity
 * without calling the transitional SQL bridge for the DELETE proof path.
 */
public final class StoragePhaseF6NativeDeleteEqualitySmoke {
    private static final String DATABASE_PATH = "storage-phase-f6-native-delete-equality-db";
    private static final String TABLE_NAME = "F6_NATIVE_DELETE_EQ";

    private StoragePhaseF6NativeDeleteEqualitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createDerbyCatalogTableAndProviderTable();
            proveNativeDeleteEqualityAndReadBack();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f6_native_delete_equality: PASS");
    }

    private static void createDerbyCatalogTableAndProviderTable() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE " + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc")) {
                statement.executeUpdate();
            }
        }

    }

    private static void proveNativeDeleteEqualityAndReadBack() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY, "true");

            insert(connection, 1, "alpha");
            insert(connection, 2, "bravo");

            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                delete.setInt(1, 1);
                require(delete.executeUpdate() == 1,
                        "Expected F6 native MVCC DELETE equality to affect one row");
            }


            try (PreparedStatement deleted = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                deleted.setInt(1, 1);
                try (ResultSet rows = deleted.executeQuery()) {
                    require(!rows.next(), "Expected F6 native DELETE equality to remove id=1");
                }
            }

            try (PreparedStatement survivor = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                survivor.setInt(1, 2);
                try (ResultSet rows = survivor.executeQuery()) {
                    require(rows.next(), "Expected F6 native DELETE equality to leave id=2 visible");
                    require(rows.getInt(1) == 2, "Unexpected surviving id after F6 DELETE");
                    require("bravo".equals(rows.getString(2)), "Unexpected surviving value after F6 DELETE");
                    require(!rows.next(), "Expected exactly one surviving row after F6 DELETE");
                }
            }

        }
    }

    private static void insert(Connection connection, int id, String value) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)")) {
            insert.setInt(1, id);
            insert.setString(2, value);
            require(insert.executeUpdate() == 1, "Expected F6 seed INSERT to affect one row");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
