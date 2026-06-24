package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccess;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

/**
 * O2 heap provider facade behavior proof.
 */
public final class StoragePhaseO2HeapProviderFacadeSmoke {
    private static final String DATABASE_PATH = "storage-phase-o2-heap-provider-facade-db";
    private static final String TABLE = "O2_HEAP_PROVIDER_FACADE";

    private StoragePhaseO2HeapProviderFacadeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveO2FacadeBacksHeapReadAndInsertRoutes();
        } finally {
            clearProofProperties();
            resetCounters();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_o2_heap_provider_facade: PASS");
    }

    private static void proveO2FacadeBacksHeapReadAndInsertRoutes() throws Exception {
        clearProofProperties();
        resetCounters();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_PROVIDER_PARITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected O2 heap provider facade table creation");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE + " VALUES (?, ?)", 1, "one") == 1,
                    "Expected first heap INSERT through O2 facade-backed route");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE + " VALUES (?, ?)", 2, "two") == 1,
                    "Expected second heap INSERT through O2 facade-backed route");
            assertSingleValue(statement, 2, "two");
            require(statement.executeUpdate(
                    "UPDATE APP." + TABLE + " SET value = 'two-o2' WHERE id = 2") == 1,
                    "Expected heap UPDATE to remain available under O1/O2 gate");
            require(statement.executeUpdate(
                    "DELETE FROM APP." + TABLE + " WHERE id = 1") == 1,
                    "Expected heap DELETE to remain available under O1/O2 gate");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertMissingId(statement, 1);
            assertSingleValue(statement, 2, "two-o2");
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM APP." + TABLE)) {
                require(rows.next(), "Expected count row after O2 heap facade route");
                require(rows.getInt(1) == 1, "Expected one row after O2 DELETE");
                require(!rows.next(), "Expected one count row after O2 heap facade route");
            }
        }

        require(EngineHeapTableAccess.facadeMutationAdapterOpenCountForTesting() >= 2,
                "Expected O2 heap INSERT route to open mutation adapter through EngineHeapTableAccess");
        require(EngineHeapTableAccess.facadeScanOpenCountForTesting() >= 1,
                "Expected O2 heap SELECT route to scan through EngineHeapTableAccess");
        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() >= 2,
                "Expected O2 to keep heap INSERT live route enabled");
        require(DelosTableScanProviderLookup.heapSelectLiveRouteBranchCountForTesting() >= 1,
                "Expected O2 to keep heap SELECT live route enabled");
        require(DelosTableScanProviderLookup.heapUpdateLiveRouteBranchCountForTesting() >= 1,
                "Expected O2 to keep heap UPDATE live route enabled");
        require(DelosTableScanProviderLookup.heapDeleteLiveRouteBranchCountForTesting() >= 1,
                "Expected O2 to keep heap DELETE live route enabled");
    }

    private static void assertSingleValue(Statement statement, int id, String value)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT value FROM APP." + TABLE + " WHERE id = " + id)) {
            require(rows.next(), "Expected row " + id + " in " + TABLE);
            require(value.equals(rows.getString(1)),
                    "Expected value " + value + " for row " + id + " in " + TABLE);
            require(!rows.next(), "Expected one row " + id + " in " + TABLE);
        }
    }

    private static void assertMissingId(Statement statement, int id)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT id FROM APP." + TABLE + " WHERE id = " + id)) {
            require(!rows.next(), "Expected missing row " + id + " in " + TABLE);
        }
    }

    private static void resetCounters() {
        EngineHeapTableAccess.resetFacadeCountersForTesting();
        DelosTableScanProviderLookup.resetHeapSelectLiveRouteForTesting();
        DelosTableScanProviderLookup.resetHeapInsertLiveRouteForTesting();
        DelosTableScanProviderLookup.resetHeapDeleteUpdateLiveRouteForTesting();
    }

    private static void clearProofProperties() {
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
