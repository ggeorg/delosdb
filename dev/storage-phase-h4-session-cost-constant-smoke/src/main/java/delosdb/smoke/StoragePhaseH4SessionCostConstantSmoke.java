package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.iapi.store.access.DelosStoreCostTuning;
import org.apache.derby.iapi.store.access.StoreCostController;
import org.apache.derby.impl.sql.compile.DelosHeapCostProofLookup;
import org.apache.derby.impl.sql.compile.DelosNativeTableCostLookup;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase H4 proof: one inherited Derby store-cost constant can be tuned for the
 * current SQL session through a SYSCS_UTIL procedure.  H4 deliberately touches
 * the existing Derby heap/btree store-cost calculation and does not introduce a
 * provider-specific optimizer decision.
 */
public final class StoragePhaseH4SessionCostConstantSmoke {
    private static final String DATABASE_PATH = "storage-phase-h4-session-cost-constant-db";
    private static final String TABLE_NAME = "H4_SESSION_COST";
    private static final double DEFAULT_UNCACHED_COST = StoreCostController.BASE_UNCACHED_ROW_FETCH_COST;
    private static final double SESSION_UNCACHED_COST = 9.75d;

    private StoragePhaseH4SessionCostConstantSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveUncachedRowFetchCostIsSessionTunable();
        } finally {
            DelosStoreCostTuning.resetForTesting();
            DelosHeapCostProofLookup.resetForTesting();
            DelosNativeTableCostLookup.resetForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_h4_session_cost_constant: PASS");
    }

    private static void proveUncachedRowFetchCostIsSessionTunable() throws Exception {
        DelosStoreCostTuning.resetForTesting();
        DelosHeapCostProofLookup.resetForTesting();
        DelosNativeTableCostLookup.resetForTesting();
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection tuned = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = tuned.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32))") == 0,
                    "Expected normal Derby heap CREATE TABLE to succeed");
            for (int i = 1; i <= 8; i++) {
                require(SmokeUtils.executePreparedUpdate(tuned,
                        "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", i, "value-" + i) == 1,
                        "Expected heap INSERT " + i + " to affect one row");
            }

            setSessionUncachedRowFetchCost(tuned, SESSION_UNCACHED_COST);
            assertCostUsedForQuery(tuned,
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id >= 1",
                    SESSION_UNCACHED_COST,
                    "tuned session should use session override");

            try (Connection defaultSession = SmokeUtils.connect(DATABASE_PATH, false)) {
                assertCostUsedForQuery(defaultSession,
                        "SELECT * FROM APP." + TABLE_NAME + " WHERE id >= 2",
                        DEFAULT_UNCACHED_COST,
                        "separate session should still use Derby default");
            }

            assertCostUsedForQuery(tuned,
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id >= 3",
                    SESSION_UNCACHED_COST,
                    "tuned session should retain override after another session runs");

            clearSessionUncachedRowFetchCost(tuned);
            assertCostUsedForQuery(tuned,
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id >= 4",
                    DEFAULT_UNCACHED_COST,
                    "clear procedure should restore Derby default for the session");
        }

        require(DelosNativeTableCostLookup.lookupCountForTesting() == 0,
                "H4 heap/store cost proof must not use MVCC native cost lookup");
        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "H4 must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
    }

    private static void setSessionUncachedRowFetchCost(Connection connection, double cost) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "CALL SYSCS_UTIL.SYSCS_SET_DELOSDB_UNCACHED_ROW_FETCH_COST(?)")) {
            statement.setDouble(1, cost);
            statement.executeUpdate();
        }
    }


    private static void clearSessionUncachedRowFetchCost(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CALL SYSCS_UTIL.SYSCS_CLEAR_DELOSDB_UNCACHED_ROW_FETCH_COST()");
        }
    }

    private static void assertCostUsedForQuery(Connection connection,
                                               String sql,
                                               double expectedCost,
                                               String label) throws Exception {
        DelosStoreCostTuning.resetForTesting();
        int count = countRows(connection, sql);
        require(count > 0, "Expected positive row count for " + label + " but was " + count);
        require(DelosStoreCostTuning.uncachedRowFetchCostLookupCountForTesting() > 0,
                "Expected Derby store cost controller to observe uncached-row-fetch cost for " + label);
        double actualCost = DelosStoreCostTuning.lastUncachedRowFetchCostForTesting();
        require(approximately(expectedCost, actualCost),
                label + " expected uncached-row-fetch cost " + expectedCost + " but observed " + actualCost);
    }

    private static int countRows(Connection connection, String sql) throws Exception {
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            while (results.next()) {
                count++;
            }
        }
        return count;
    }

    private static boolean approximately(double expected, double actual) {
        return Math.abs(expected - actual) < 0.000_001d;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
