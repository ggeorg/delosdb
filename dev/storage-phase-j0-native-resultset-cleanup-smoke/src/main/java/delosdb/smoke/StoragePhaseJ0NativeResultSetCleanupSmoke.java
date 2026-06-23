package delosdb.smoke;

import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * J0 closeout proof: native result-set boundary cleanup keeps existing behavior.
 */
public final class StoragePhaseJ0NativeResultSetCleanupSmoke {
    private static final String DATABASE_PATH = "storage-phase-j0-native-resultset-cleanup-db";
    private static final String TABLE_NAME = "J0_AND_CONJUNCTION";

    private StoragePhaseJ0NativeResultSetCleanupSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveNativeAndConjunctionShape();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_j0_native_resultset_cleanup: PASS");
    }

    private static void proveNativeAndConjunctionShape() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME
                            + " (id INT, kind VARCHAR(16), value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc to succeed");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?, ?)",
                    1, "x", "alpha") == 1,
                    "Expected native INSERT for matching row to affect one row");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?, ?)",
                    1, "y", "wrong-kind") == 1,
                    "Expected native INSERT for non-matching kind row to affect one row");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?, ?)",
                    2, "x", "wrong-id") == 1,
                    "Expected native INSERT for non-matching id row to affect one row");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT value FROM APP." + TABLE_NAME + " WHERE id = ? AND kind = ?")) {
            statement.setInt(1, 1);
            statement.setString(2, "x");
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "Expected one row for ID = 1 AND KIND = 'x'");
                String value = rows.getString(1);
                require("alpha".equals(value), "Expected alpha row for AND conjunction but saw " + value);
                require(!rows.next(), "Expected AND conjunction to filter out wrong-id and wrong-kind rows");
            }
        }
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
