package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccess;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

/**
 * O3 provider-truth behavior proof.
 *
 * <p>O3 proves the current truth after O2: delos_mvcc remains a live native
 * provider, and default heap has live supported read/write execution routes
 * behind the heap provider-parity gate. Heap locking/reservation remains
 * Derby-owned and is not claimed as MVCC-style parity.</p>
 */
public final class StoragePhaseO3ProviderTruthCleanupSmoke {
    private static final String DATABASE_PATH = "storage-phase-o3-provider-truth-cleanup-db";
    private static final String MVCC_TABLE = "O3_MVCC_PROVIDER_TRUTH";
    private static final String HEAP_TABLE = "O3_HEAP_PROVIDER_TRUTH";

    private StoragePhaseO3ProviderTruthCleanupSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveMvccAndHeapAreBothLiveProviderRoutes();
        } finally {
            clearProofProperties();
            resetCounters();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_o3_provider_truth_cleanup: PASS");
    }

    private static void proveMvccAndHeapAreBothLiveProviderRoutes() throws Exception {
        clearProofProperties();
        resetCounters();
        enableMvccNativeRoute();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_PROVIDER_PARITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected delos_mvcc table creation for O3 provider truth");
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected ordinary heap table creation for O3 provider truth");

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
                    "Expected heap INSERT through live heap route");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_TABLE + " VALUES (?, ?)", 20, "heap-twenty") == 1,
                    "Expected second heap INSERT through live heap route");
            require(statement.executeUpdate(
                    "UPDATE APP." + HEAP_TABLE + " SET value = 'heap-twenty-live' WHERE id = 20") == 1,
                    "Expected heap UPDATE through supported live route");
            require(statement.executeUpdate(
                    "DELETE FROM APP." + HEAP_TABLE + " WHERE id = 10") == 1,
                    "Expected heap DELETE through supported live route");

            assertSingleValue(statement, MVCC_TABLE, 2, "mvcc-two-live");
            assertMissingId(statement, MVCC_TABLE, 1);
            assertSingleValue(statement, HEAP_TABLE, 20, "heap-twenty-live");
            assertMissingId(statement, HEAP_TABLE, 10);
        }

        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting()
                        .filter(result -> result.isProvider("delos_mvcc"))
                        .isPresent(),
                "Expected O3 to prove live delos_mvcc provider route");
        require(DelosTableScanProviderLookup.heapSelectLiveRouteBranchCountForTesting() >= 1,
                "Expected O3 heap SELECT live route to be reached");
        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() >= 2,
                "Expected O3 heap INSERT live route to be reached");
        require(DelosTableScanProviderLookup.heapUpdateLiveRouteBranchCountForTesting() >= 1,
                "Expected O3 heap UPDATE live route to be reached");
        require(DelosTableScanProviderLookup.heapDeleteLiveRouteBranchCountForTesting() >= 1,
                "Expected O3 heap DELETE live route to be reached");
        require(EngineHeapTableAccess.facadeScanOpenCountForTesting() >= 1,
                "Expected O3 heap SELECT to pass through EngineHeapTableAccess facade");
        require(EngineHeapTableAccess.facadeMutationAdapterOpenCountForTesting() >= 2,
                "Expected O3 heap INSERT to pass through EngineHeapTableAccess mutation facade");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapSelectLiveRouteLookupForTesting(),
                "Expected O3 heap SELECT lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapInsertLiveRouteLookupForTesting(),
                "Expected O3 heap INSERT lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapUpdateLiveRouteLookupForTesting(),
                "Expected O3 heap UPDATE lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapDeleteLiveRouteLookupForTesting(),
                "Expected O3 heap DELETE lookup to be default-provider heap");
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
