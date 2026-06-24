package delosdb.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import org.apache.derby.impl.services.storetypes.EngineHeapDerbyAccessSupport;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccess;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

/**
 * O4 proof: the stale heap proof-adapter source is retired while the O3
 * two-live-provider behavior remains intact.
 */
public final class StoragePhaseO4RetireHeapProofAdapterSmoke {
    private static final String DATABASE_PATH = "storage-phase-o4-retire-heap-proof-adapter-db";
    private static final String MVCC_TABLE = "O4_MVCC_PROVIDER_TRUTH";
    private static final String HEAP_TABLE = "O4_HEAP_PROVIDER_TRUTH";

    private StoragePhaseO4RetireHeapProofAdapterSmoke() {
    }

    public static void main(String[] args) throws Exception {
        proveStaleProofAdapterSourceIsGone();
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveProviderBehaviorSurvivesRename();
        } finally {
            clearProofProperties();
            resetCounters();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_o4_retire_heap_proof_adapter: PASS");
    }

    private static void proveStaleProofAdapterSourceIsGone() {
        require(!Files.exists(Path.of(
                        "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessProof.java")),
                "O4 cleanup must remove stale EngineHeapTableAccessProof.java");
        require("heap".equals(EngineHeapDerbyAccessSupport.PROVIDER_NAME),
                "Expected heap provider name to come from EngineHeapDerbyAccessSupport");
    }

    private static void proveProviderBehaviorSurvivesRename() throws Exception {
        clearProofProperties();
        resetCounters();
        enableMvccNativeRoute();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_PROVIDER_PARITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected delos_mvcc table creation for O4 provider truth");
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected ordinary heap table creation for O4 provider truth");

            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?)", 1, "mvcc-one") == 1,
                    "Expected delos_mvcc native INSERT row 1");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?)", 2, "mvcc-two") == 1,
                    "Expected delos_mvcc native INSERT row 2");
            require(statement.executeUpdate(
                    "UPDATE APP." + MVCC_TABLE + " SET value = 'mvcc-two-live' WHERE id = 2") == 1,
                    "Expected delos_mvcc native UPDATE");
            require(statement.executeUpdate(
                    "DELETE FROM APP." + MVCC_TABLE + " WHERE id = 1") == 1,
                    "Expected delos_mvcc native DELETE");

            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_TABLE + " VALUES (?, ?)", 10, "heap-ten") == 1,
                    "Expected heap INSERT through live heap route after O4 rename");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_TABLE + " VALUES (?, ?)", 20, "heap-twenty") == 1,
                    "Expected second heap INSERT through live heap route after O4 rename");
            require(statement.executeUpdate(
                    "UPDATE APP." + HEAP_TABLE + " SET value = 'heap-twenty-live' WHERE id = 20") == 1,
                    "Expected heap UPDATE through supported live route after O4 rename");
            require(statement.executeUpdate(
                    "DELETE FROM APP." + HEAP_TABLE + " WHERE id = 10") == 1,
                    "Expected heap DELETE through supported live route after O4 rename");

            assertSingleValue(statement, MVCC_TABLE, 2, "mvcc-two-live");
            assertMissingId(statement, MVCC_TABLE, 1);
            assertSingleValue(statement, HEAP_TABLE, 20, "heap-twenty-live");
            assertMissingId(statement, HEAP_TABLE, 10);
        }

        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting()
                        .filter(result -> result.isProvider("delos_mvcc"))
                        .isPresent(),
                "Expected O4 to preserve live delos_mvcc provider route");
        require(DelosTableScanProviderLookup.heapSelectLiveRouteBranchCountForTesting() >= 1,
                "Expected O4 heap SELECT live route to be reached");
        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() >= 2,
                "Expected O4 heap INSERT live route to be reached");
        require(DelosTableScanProviderLookup.heapUpdateLiveRouteBranchCountForTesting() >= 1,
                "Expected O4 heap UPDATE live route to be reached");
        require(DelosTableScanProviderLookup.heapDeleteLiveRouteBranchCountForTesting() >= 1,
                "Expected O4 heap DELETE live route to be reached");
        require(EngineHeapTableAccess.facadeScanOpenCountForTesting() >= 1,
                "Expected O4 heap SELECT to pass through EngineHeapTableAccess facade");
        require(EngineHeapTableAccess.facadeMutationAdapterOpenCountForTesting() >= 2,
                "Expected O4 heap INSERT to pass through EngineHeapTableAccess mutation facade");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapSelectLiveRouteLookupForTesting(),
                "Expected O4 heap SELECT lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapInsertLiveRouteLookupForTesting(),
                "Expected O4 heap INSERT lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapUpdateLiveRouteLookupForTesting(),
                "Expected O4 heap UPDATE lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapDeleteLiveRouteLookupForTesting(),
                "Expected O4 heap DELETE lookup to be default-provider heap");
    }

    private static void enableMvccNativeRoute() {
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY, "true");
    }

    private static void assertDefaultProvider(
            Optional<DelosTableScanProviderLookup.Result> lookup,
            String message) {
        require(lookup.isPresent() && lookup.get().isDefaultStorageProvider(), message);
    }

    private static void assertSingleValue(Statement statement, String table, int id, String value)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT value FROM APP." + table + " WHERE id = " + id)) {
            require(rows.next(), "Expected row " + id + " in " + table);
            require(value.equals(rows.getString(1)),
                    "Expected value " + value + " for row " + id + " in " + table);
            require(!rows.next(), "Expected one row " + id + " in " + table);
        }
    }

    private static void assertMissingId(Statement statement, String table, int id)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT id FROM APP." + table + " WHERE id = " + id)) {
            require(!rows.next(), "Expected missing row " + id + " in " + table);
        }
    }

    private static void resetCounters() {
        EngineHeapTableAccess.resetFacadeCountersForTesting();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        DelosTableScanProviderLookup.resetHeapSelectLiveRouteForTesting();
        DelosTableScanProviderLookup.resetHeapInsertLiveRouteForTesting();
        DelosTableScanProviderLookup.resetHeapDeleteUpdateLiveRouteForTesting();
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_PROVIDER_PARITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_SELECT_LIVE_ROUTE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_INSERT_LIVE_ROUTE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_DELETE_UPDATE_LIVE_ROUTE_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
