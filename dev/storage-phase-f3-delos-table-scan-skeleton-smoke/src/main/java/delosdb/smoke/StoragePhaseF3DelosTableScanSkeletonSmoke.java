package delosdb.smoke;

import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Phase F3.2 proof: a generated Derby table-scan activation can reach a
 * Delos-owned NoPutResultSet through the existing ResultSetFactory table-scan
 * call shape.  The Delos result set is still a skeleton and must fail loudly;
 * F4 replaces the sentinel with real MVCC row materialization.
 */
public final class StoragePhaseF3DelosTableScanSkeletonSmoke {
    private static final String DATABASE_PATH = "storage-phase-f3-delos-table-scan-skeleton-db";
    private static final String TABLE_NAME = "F3_DELOS_SCAN_SKELETON";

    private StoragePhaseF3DelosTableScanSkeletonSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createNativeDerbyTableAndRow();
            SmokeUtils.shutdown(DATABASE_PATH);
            proveDelosSkeletonBranchAfterRestart();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_SKELETON_BRANCH_PROPERTY);
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f3_delos_table_scan_skeleton: PASS");
    }

    private static void createNativeDerbyTableAndRow() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            executePrepared(connection,
                    "CREATE TABLE " + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc");
            executePrepared(connection,
                    "INSERT INTO " + TABLE_NAME + " VALUES (1, 'alpha')");
        }
    }

    private static void proveDelosSkeletonBranchAfterRestart() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_SKELETON_BRANCH_PROPERTY, "true");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, value FROM " + TABLE_NAME + " WHERE id = ?")) {
                statement.setInt(1, 1);
                statement.executeQuery();
                throw new IllegalStateException("Expected DelosTableScanResultSet skeleton sentinel to fail loudly");
            } catch (SQLException expected) {
                require(contains(expected, DelosTableScanProviderLookup.FACTORY_SKELETON_REACHED_MESSAGE),
                        "Expected DelosTableScanResultSet skeleton sentinel, but saw: " + expected);
            }

            require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                    "Expected GenericResultSetFactory probe to observe the table scan before returning the skeleton");
            require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isPresent(),
                    "Expected non-default provider lookup before returning DelosTableScanResultSet skeleton");
        }
    }

    private static boolean contains(Throwable error, String text) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
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
