package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.compile.DelosHeapCostProofLookup;
import org.apache.derby.impl.sql.compile.DelosNativeTableCostLookup;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase H3 proof: inherited Derby heap cost data can be mapped into the same
 * Delos table-cost record shape through the proof-only heap adapter.  H3 does
 * not route heap SQL execution through Delos table access and does not replace
 * Derby optimizer costs.
 */
public final class StoragePhaseH3HeapCostProofSmoke {
    private static final String DATABASE_PATH = "storage-phase-h3-heap-cost-proof-db";
    private static final String TABLE_NAME = "H3_HEAP_COST_PROOF";

    private StoragePhaseH3HeapCostProofSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveHeapCostMapsThroughProofAdapterOnly();
        } finally {
            clearProofProperties();
            DelosHeapCostProofLookup.resetForTesting();
            DelosNativeTableCostLookup.resetForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_h3_heap_cost_proof: PASS");
    }

    private static void proveHeapCostMapsThroughProofAdapterOnly() throws Exception {
        clearProofProperties();
        DelosHeapCostProofLookup.resetForTesting();
        DelosNativeTableCostLookup.resetForTesting();
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32))") == 0,
                    "Expected normal Derby heap CREATE TABLE to succeed");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 1, "one") == 1,
                    "Expected first heap INSERT to affect one row");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 2, "two") == 1,
                    "Expected second heap INSERT to affect one row");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 3, "three") == 1,
                    "Expected third heap INSERT to affect one row");

            DelosHeapCostProofLookup.resetForTesting();
            System.setProperty(DelosHeapCostProofLookup.HEAP_COST_PROOF_PROBE_PROPERTY, "true");
            int rowCount = countRows(connection, "SELECT * FROM APP." + TABLE_NAME + " WHERE id >= ?", 1);
            require(rowCount == 3, "Expected heap SELECT to return 3 rows but returned " + rowCount);
        }

        DelosHeapCostProofLookup.Result result = DelosHeapCostProofLookup.lastLookupForTesting()
                .orElseThrow(() -> new IllegalStateException("Expected H3 heap cost proof lookup result"));
        require(DelosHeapCostProofLookup.lookupCountForTesting() > 0,
                "Expected H3 heap proof lookup count to be positive");
        require(("APP." + TABLE_NAME).equals(result.qualifiedTableName()),
                "Expected H3 lookup for APP." + TABLE_NAME + " but was " + result.qualifiedTableName());
        require("heap".equals(result.storageProviderName()),
                "Expected heap provider but was " + result.storageProviderName());
        require(result.costableCapabilityAdvertised(),
                "Expected proof-only heap adapter to advertise COSTABLE");
        require(result.proofOnly(), "H3 heap mapping must remain proof-only");
        require(!result.consumedByDerbyOptimizer(),
                "H3 heap proof must not consume or replace Derby optimizer costs");
        require("proof-only".equals(result.decision()),
                "Expected proof-only H3 decision but was " + result.decision());
        require(result.logicalRowCount() == result.derbyRowCount(),
                "Expected logicalRowCount to map from Derby row count");
        require(result.visibleRowCount() == result.logicalRowCount(),
                "Expected heap visibleRowCount to equal logicalRowCount");
        require(result.physicalVersionCount() == result.logicalRowCount(),
                "Expected heap physicalVersionCount to equal logicalRowCount in proof-only mapping");
        require(result.deadVersionEstimate() == 0L,
                "Expected heap deadVersionEstimate 0 but was " + result.deadVersionEstimate());
        require(result.estimatedFullScanCost() >= 1L,
                "Expected positive heap estimatedFullScanCost but was " + result.estimatedFullScanCost());
        require(Double.isFinite(result.derbyEstimatedCost()) && result.derbyEstimatedCost() >= 0.0d,
                "Expected finite non-negative Derby cost but was " + result.derbyEstimatedCost());
        require(DelosNativeTableCostLookup.lookupCountForTesting() == 0,
                "H3 heap proof must not use MVCC native cost lookup");
        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "H3 heap proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
    }

    private static int countRows(Connection connection, String sql, int id) throws Exception {
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, id);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosHeapCostProofLookup.HEAP_COST_PROOF_PROBE_PROPERTY);
        System.clearProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_PROBE_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
