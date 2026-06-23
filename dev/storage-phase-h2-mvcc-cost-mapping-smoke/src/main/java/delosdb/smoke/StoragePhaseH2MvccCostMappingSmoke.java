package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.compile.DelosNativeTableCostLookup;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase H2 proof: provider-backed MVCC stats are mapped into the native Derby
 * cost-estimation path as a diagnostic-only table-cost result.  H2 still does
 * not replace Derby optimizer costs.
 */
public final class StoragePhaseH2MvccCostMappingSmoke {
    private static final String DATABASE_PATH = "storage-phase-h2-mvcc-cost-mapping-db";
    private static final String TABLE_NAME = "H2_COST_MAPPING";

    private StoragePhaseH2MvccCostMappingSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveMvccStatsReachNativeCostPath();
        } finally {
            clearProofProperties();
            DelosNativeTableCostLookup.resetForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_h2_mvcc_cost_mapping: PASS");
    }

    private static void proveMvccStatsReachNativeCostPath() throws Exception {
        clearProofProperties();
        DelosNativeTableCostLookup.resetForTesting();
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY, "true");
        System.setProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_PROBE_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc to succeed");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 1, "one") == 1,
                    "Expected first native INSERT to affect one row");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 2, "two") == 1,
                    "Expected second native INSERT to affect one row");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 3, "three") == 1,
                    "Expected third native INSERT to affect one row");

            int rowCount = countRows(connection, "SELECT * FROM APP." + TABLE_NAME);
            require(rowCount == 3, "Expected native SELECT * to return 3 rows but returned " + rowCount);
        }

        DelosNativeTableCostLookup.Result result = DelosNativeTableCostLookup.lastLookupForTesting()
                .orElseThrow(() -> new IllegalStateException("Expected H2 native table cost lookup result"));
        require(DelosNativeTableCostLookup.lookupCountForTesting() > 0,
                "Expected H2 native table cost lookup count to be positive");
        require(("APP." + TABLE_NAME).equals(result.qualifiedTableName()),
                "Expected H2 lookup for APP." + TABLE_NAME + " but was " + result.qualifiedTableName());
        require("delos_mvcc".equals(result.storageProviderName()),
                "Expected delos_mvcc provider but was " + result.storageProviderName());
        require(result.logicalRowCount() == 3L,
                "Expected logicalRowCount 3 but was " + result.logicalRowCount());
        require(result.visibleRowCount() == 3L,
                "Expected visibleRowCount 3 but was " + result.visibleRowCount());
        require(result.physicalVersionCount() == 3L,
                "Expected physicalVersionCount 3 but was " + result.physicalVersionCount());
        require(result.deadVersionEstimate() == 0L,
                "Expected deadVersionEstimate 0 but was " + result.deadVersionEstimate());
        require(result.estimatedFullScanCost() == 3L,
                "Expected estimatedFullScanCost 3 but was " + result.estimatedFullScanCost());
        require(!result.consumedByDerbyOptimizer(),
                "H2 must not consume or replace Derby optimizer costs");
        require("diagnostic-only".equals(result.decision()),
                "Expected diagnostic-only H2 decision but was " + result.decision());
        require(Double.isFinite(result.derbyEstimatedCost()) && result.derbyEstimatedCost() >= 0.0d,
                "Expected finite non-negative Derby cost but was " + result.derbyEstimatedCost());
        require(Double.isFinite(result.derbyRowCount()) && result.derbyRowCount() >= 0.0d,
                "Expected finite non-negative Derby row count but was " + result.derbyRowCount());

        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "H2 cost mapping proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
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

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_PROBE_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
