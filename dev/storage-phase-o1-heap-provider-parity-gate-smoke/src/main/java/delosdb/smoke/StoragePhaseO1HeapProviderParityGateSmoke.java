package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

/**
 * O1 heap provider-parity gate proof.
 */
public final class StoragePhaseO1HeapProviderParityGateSmoke {
    private static final String DATABASE_PATH = "storage-phase-o1-heap-provider-parity-gate-db";
    private static final String OFF_TABLE = "O1_HEAP_PARITY_OFF";
    private static final String LIVE_TABLE = "O1_HEAP_PARITY_LIVE";

    private StoragePhaseO1HeapProviderParityGateSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveDisabledLeavesHeapOnDerbyRoutes();
            proveUnifiedGateEnablesHeapReadWriteRoutes();
        } finally {
            clearProofProperties();
            resetRouteCounters();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_o1_heap_provider_parity_gate: PASS");
    }

    private static void proveDisabledLeavesHeapOnDerbyRoutes() throws Exception {
        clearProofProperties();
        resetRouteCounters();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + OFF_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected O1 disabled table creation");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + OFF_TABLE + " VALUES (?, ?)", 1, "off-one") == 1,
                    "Expected ordinary heap INSERT while O1 is disabled");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + OFF_TABLE + " VALUES (?, ?)", 2, "off-two") == 1,
                    "Expected second ordinary heap INSERT while O1 is disabled");
            assertSingleValue(statement, OFF_TABLE, 1, "off-one");
            require(statement.executeUpdate(
                    "UPDATE APP." + OFF_TABLE + " SET value = 'off-two-updated' WHERE id = 2") == 1,
                    "Expected ordinary heap UPDATE while O1 is disabled");
            require(statement.executeUpdate(
                    "DELETE FROM APP." + OFF_TABLE + " WHERE id = 1") == 1,
                    "Expected ordinary heap DELETE while O1 is disabled");
        }

        require(DelosTableScanProviderLookup.heapSelectLiveRouteBranchCountForTesting() == 0,
                "Expected heap SELECT live route not to run while O1 is disabled");
        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() == 0,
                "Expected heap INSERT live route not to run while O1 is disabled");
        require(DelosTableScanProviderLookup.heapUpdateLiveRouteBranchCountForTesting() == 0,
                "Expected heap UPDATE live route not to run while O1 is disabled");
        require(DelosTableScanProviderLookup.heapDeleteLiveRouteBranchCountForTesting() == 0,
                "Expected heap DELETE live route not to run while O1 is disabled");
    }

    private static void proveUnifiedGateEnablesHeapReadWriteRoutes() throws Exception {
        clearProofProperties();
        resetRouteCounters();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_PROVIDER_PARITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + LIVE_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected O1 live table creation");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + LIVE_TABLE + " VALUES (?, ?)", 1, "one") == 1,
                    "Expected first heap INSERT through unified O1 gate");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + LIVE_TABLE + " VALUES (?, ?)", 2, "two") == 1,
                    "Expected second heap INSERT through unified O1 gate");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + LIVE_TABLE + " VALUES (?, ?)", 3, "three") == 1,
                    "Expected third heap INSERT through unified O1 gate");
            assertSingleValue(statement, LIVE_TABLE, 2, "two");
            require(statement.executeUpdate(
                    "UPDATE APP." + LIVE_TABLE + " SET value = 'two-live' WHERE id = 2") == 1,
                    "Expected heap UPDATE through unified O1 gate");
            require(statement.executeUpdate(
                    "DELETE FROM APP." + LIVE_TABLE + " WHERE id = 1") == 1,
                    "Expected heap DELETE through unified O1 gate");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertMissingId(statement, LIVE_TABLE, 1);
            assertSingleValue(statement, LIVE_TABLE, 2, "two-live");
            assertSingleValue(statement, LIVE_TABLE, 3, "three");
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM APP." + LIVE_TABLE)) {
                require(rows.next(), "Expected count row after O1 heap read/write route");
                require(rows.getInt(1) == 2, "Expected two rows after O1 DELETE");
                require(!rows.next(), "Expected one count row after O1 heap read/write route");
            }
        }

        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() >= 3,
                "Expected unified O1 gate to enable heap INSERT live route");
        require(DelosTableScanProviderLookup.heapSelectLiveRouteBranchCountForTesting() >= 1,
                "Expected unified O1 gate to enable heap SELECT live route");
        require(DelosTableScanProviderLookup.heapUpdateLiveRouteBranchCountForTesting() >= 1,
                "Expected unified O1 gate to enable heap UPDATE live route");
        require(DelosTableScanProviderLookup.heapDeleteLiveRouteBranchCountForTesting() >= 1,
                "Expected unified O1 gate to enable heap DELETE live route");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapInsertLiveRouteLookupForTesting(),
                "Expected O1 heap INSERT lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapSelectLiveRouteLookupForTesting(),
                "Expected O1 heap SELECT lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapUpdateLiveRouteLookupForTesting(),
                "Expected O1 heap UPDATE lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapDeleteLiveRouteLookupForTesting(),
                "Expected O1 heap DELETE lookup to be default-provider heap");
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

    private static void resetRouteCounters() {
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
