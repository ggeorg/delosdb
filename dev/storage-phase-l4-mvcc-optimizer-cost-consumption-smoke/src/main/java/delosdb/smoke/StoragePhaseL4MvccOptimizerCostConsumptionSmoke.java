package delosdb.smoke;

import org.apache.derby.impl.sql.compile.DelosHeapCostProofLookup;
import org.apache.derby.impl.sql.compile.DelosNativeTableCostLookup;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * L4 proof: delos_mvcc provider table-cost estimates can be consumed by Derby's
 * optimizer at the existing FromBaseTable.estimateCost boundary. Heap remains
 * Derby-native and the heap proof adapter remains diagnostic/proof-only.
 */
public final class StoragePhaseL4MvccOptimizerCostConsumptionSmoke {
    private static final String DATABASE_PATH = "storage-phase-l4-mvcc-optimizer-cost-consumption-db";
    private static final String MVCC_TABLE = "L4_MVCC_COST";
    private static final String HEAP_TABLE = "L4_HEAP_COST";

    private StoragePhaseL4MvccOptimizerCostConsumptionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveSourceHasMvccOnlyOptimizerConsumptionGate();
            proveMvccProviderCostIsConsumedByOptimizerEstimate();
            proveHeapStillDoesNotUseNativeMvccCostConsumption();
        } finally {
            clearProofProperties();
            DelosNativeTableCostLookup.resetForTesting();
            DelosHeapCostProofLookup.resetForTesting();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_l4_mvcc_optimizer_cost_consumption: PASS");
    }

    private static void proveSourceHasMvccOnlyOptimizerConsumptionGate() throws Exception {
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/FromBaseTable.java"), List.of(
                "DelosNativeTableCostLookup.observeIfEnabled(tableDescriptor, costEst)",
                "DelosHeapCostProofLookup.observeIfEnabled(tableDescriptor, costEst)"));
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/DelosNativeTableCostLookup.java"), List.of(
                "NATIVE_TABLE_COST_PROBE_PROPERTY",
                "delosdb.storage.phase.h2.nativeTableCostProbe",
                "NATIVE_TABLE_COST_CONSUMPTION_PROPERTY",
                "delosdb.storage.phase.l4.nativeOptimizerCostConsumption",
                "boolean consumptionEnabled",
                "safeToConsume(providerEstimate)",
                "derbyCostEstimate.setCost(providerCost, providerRows, providerRows)",
                "consumedByDerbyOptimizer ? \"consumed\" : \"diagnostic-only\"",
                "isDelosMvcc(tableDescriptor.getStorageProviderName())"));
        assertSourceDoesNotContain(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/DelosHeapCostProofLookup.java"), List.of(
                "NATIVE_TABLE_COST_CONSUMPTION_PROPERTY",
                "nativeOptimizerCostConsumption",
                "setCost("));
        assertSourceContains(Path.of("docs/storage-phase-l4-mvcc-optimizer-cost-consumption.md"), List.of(
                "L4 consumes provider cost for delos_mvcc only",
                "No heap routing change",
                "No heap cost consumption",
                "No mutation behavior change",
                "No locking behavior change"));
    }

    private static void proveMvccProviderCostIsConsumedByOptimizerEstimate() throws Exception {
        clearProofProperties();
        DelosNativeTableCostLookup.resetForTesting();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_CONSUMPTION_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected explicit delos_mvcc CREATE TABLE to succeed");
            for (int i = 1; i <= 9; i++) {
                require(SmokeUtils.executePreparedUpdate(connection,
                        "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?)", i, "mvcc-" + i) == 1,
                        "Expected delos_mvcc INSERT " + i + " to affect one row");
            }
        }

        DelosNativeTableCostLookup.resetForTesting();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            int rows = countRows(connection, "SELECT * FROM APP." + MVCC_TABLE);
            require(rows == 9, "Expected delos_mvcc SELECT * to return nine rows, saw " + rows);
        }

        DelosNativeTableCostLookup.Result lookup = DelosNativeTableCostLookup.lastLookupForTesting()
                .orElseThrow(() -> new IllegalStateException(
                        "Expected delos_mvcc optimizer-cost lookup to be recorded"));
        require(DelosNativeTableCostLookup.lookupCountForTesting() > 0,
                "Expected at least one delos_mvcc optimizer-cost lookup");
        require("APP.".concat(MVCC_TABLE).equals(lookup.qualifiedTableName()),
                "Expected lookup for APP." + MVCC_TABLE + " but saw " + lookup.qualifiedTableName());
        require("delos_mvcc".equals(lookup.storageProviderName()),
                "Expected delos_mvcc provider in optimizer-cost lookup");
        require(lookup.visibleRowCount() == 9L,
                "Expected provider visible row count to be nine but was " + lookup.visibleRowCount());
        require(lookup.logicalRowCount() == 9L,
                "Expected provider logical row count to be nine but was " + lookup.logicalRowCount());
        require(lookup.estimatedFullScanCost() >= lookup.visibleRowCount(),
                "Expected provider full-scan cost to cover visible rows");
        require(lookup.consumedByDerbyOptimizer(),
                "Expected delos_mvcc provider cost to be consumed by Derby optimizer estimate");
        require("consumed".equals(lookup.decision()),
                "Expected consumed decision but was " + lookup.decision());
        require(approximately((double) lookup.estimatedFullScanCost(), lookup.optimizerEstimatedCost()),
                "Expected optimizer cost to be provider full-scan cost");
        require(approximately((double) lookup.visibleRowCount(), lookup.optimizerRowCount()),
                "Expected optimizer row count to be provider visible row count");
        require(approximately((double) lookup.visibleRowCount(), lookup.optimizerSingleScanRowCount()),
                "Expected optimizer single-scan row count to be provider visible row count");

        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isPresent(),
                "Expected delos_mvcc SELECT to remain on native result-set route");
    }

    private static void proveHeapStillDoesNotUseNativeMvccCostConsumption() throws Exception {
        clearProofProperties();
        DelosNativeTableCostLookup.resetForTesting();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        System.setProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_CONSUMPTION_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected ordinary heap CREATE TABLE to succeed");
            for (int i = 1; i <= 3; i++) {
                require(SmokeUtils.executePreparedUpdate(connection,
                        "INSERT INTO APP." + HEAP_TABLE + " VALUES (?, ?)", i, "heap-" + i) == 1,
                        "Expected heap INSERT " + i + " to affect one row");
            }
        }

        DelosNativeTableCostLookup.resetForTesting();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            int rows = countRows(connection, "SELECT * FROM APP." + HEAP_TABLE);
            require(rows == 3, "Expected ordinary heap SELECT * to return three rows, saw " + rows);
        }

        require(DelosNativeTableCostLookup.lookupCountForTesting() == 0,
                "Expected heap SELECT not to record delos_mvcc optimizer-cost consumption");
        require(DelosNativeTableCostLookup.lastLookupForTesting().isEmpty(),
                "Expected no delos_mvcc cost lookup for heap table");
        DelosTableScanProviderLookup.Result observed = DelosTableScanProviderLookup.lastFactoryLookupForTesting()
                .orElseThrow(() -> new IllegalStateException("Expected heap table-scan provider lookup observation"));
        require(observed.isDefaultStorageProvider(),
                "Expected ordinary heap table to remain Derby default provider");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isEmpty(),
                "Expected ordinary heap SELECT not to use native delos_mvcc route");
    }

    private static int countRows(Connection connection, String sql) throws Exception {
        int count = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                count++;
            }
        }
        return count;
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_PROBE_PROPERTY);
        System.clearProperty(DelosNativeTableCostLookup.NATIVE_TABLE_COST_CONSUMPTION_PROPERTY);
        System.clearProperty(DelosHeapCostProofLookup.HEAP_COST_PROOF_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_SKELETON_BRANCH_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
    }

    private static void assertSourceContains(Path path, List<String> expectedMarkers) throws Exception {
        String source = Files.readString(path);
        for (String marker : expectedMarkers) {
            require(source.contains(marker), "Expected " + path + " to contain marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path path, List<String> forbiddenMarkers) throws Exception {
        String source = Files.readString(path);
        for (String marker : forbiddenMarkers) {
            require(!source.contains(marker), "Expected " + path + " not to contain marker: " + marker);
        }
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
