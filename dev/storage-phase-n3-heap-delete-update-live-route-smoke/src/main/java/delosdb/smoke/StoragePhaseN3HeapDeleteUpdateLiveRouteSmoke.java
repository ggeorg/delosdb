package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

/**
 * N3 heap DELETE / UPDATE live-route proof.
 */
public final class StoragePhaseN3HeapDeleteUpdateLiveRouteSmoke {
    private static final String DATABASE_PATH = "storage-phase-n3-heap-delete-update-live-route-db";
    private static final String DISABLED_TABLE = "N3_HEAP_DU_OFF";
    private static final String LIVE_TABLE = "N3_HEAP_DU_LIVE";

    private StoragePhaseN3HeapDeleteUpdateLiveRouteSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveDisabledLeavesHeapDeleteUpdateOnDerbyRoute();
            proveEnabledRoutesSupportedHeapDeleteUpdate();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetHeapDeleteUpdateLiveRouteForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_n3_heap_delete_update_live_route: PASS");
    }

    private static void proveDisabledLeavesHeapDeleteUpdateOnDerbyRoute() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetHeapDeleteUpdateLiveRouteForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + DISABLED_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected disabled proof table creation");
            require(statement.executeUpdate(
                    "INSERT INTO APP." + DISABLED_TABLE + " VALUES (1, 'one'), (2, 'two')") == 2,
                    "Expected disabled proof seed rows");
            require(statement.executeUpdate(
                    "UPDATE APP." + DISABLED_TABLE + " SET value = 'two-updated' WHERE id = 2") == 1,
                    "Expected Derby UPDATE with N3 flag disabled");
            require(statement.executeUpdate(
                    "DELETE FROM APP." + DISABLED_TABLE + " WHERE id = 1") == 1,
                    "Expected Derby DELETE with N3 flag disabled");
        }

        require(DelosTableScanProviderLookup.heapDeleteLiveRouteBranchCountForTesting() == 0,
                "Expected heap DELETE live route not to run while N3 flag is disabled");
        require(DelosTableScanProviderLookup.heapUpdateLiveRouteBranchCountForTesting() == 0,
                "Expected heap UPDATE live route not to run while N3 flag is disabled");
    }

    private static void proveEnabledRoutesSupportedHeapDeleteUpdate() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetHeapDeleteUpdateLiveRouteForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_DELETE_UPDATE_LIVE_ROUTE_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + LIVE_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected live proof table creation");
            require(statement.executeUpdate(
                    "CREATE INDEX N3_HEAP_DU_LIVE_IX ON APP." + LIVE_TABLE + "(id)") == 0,
                    "Expected Derby index creation to remain Derby-owned");
            require(statement.executeUpdate(
                    "INSERT INTO APP." + LIVE_TABLE + " VALUES (1, 'one'), (2, 'two'), (3, 'three')") == 3,
                    "Expected live proof seed rows");
            require(statement.executeUpdate(
                    "UPDATE APP." + LIVE_TABLE + " SET value = 'two-live' WHERE id = 2") == 1,
                    "Expected supported heap UPDATE through N3 live route");
            require(statement.executeUpdate(
                    "DELETE FROM APP." + LIVE_TABLE + " WHERE id = 1") == 1,
                    "Expected supported heap DELETE through N3 live route");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertMissingId(statement, LIVE_TABLE, 1);
            assertValue(statement, LIVE_TABLE, 2, "two-live");
            assertValue(statement, LIVE_TABLE, 3, "three");
            try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM APP." + LIVE_TABLE)) {
                require(rows.next(), "Expected count row after N3 heap DELETE/UPDATE route");
                require(rows.getInt(1) == 2, "Expected two rows after N3 DELETE");
            }
        }

        require(DelosTableScanProviderLookup.heapDeleteLiveRouteBranchCountForTesting() >= 1,
                "Expected property-gated heap DELETE live route to be reached");
        require(DelosTableScanProviderLookup.heapUpdateLiveRouteBranchCountForTesting() >= 1,
                "Expected property-gated heap UPDATE live route to be reached");
        Optional<DelosTableScanProviderLookup.Result> deleteLookup =
                DelosTableScanProviderLookup.lastHeapDeleteLiveRouteLookupForTesting();
        Optional<DelosTableScanProviderLookup.Result> updateLookup =
                DelosTableScanProviderLookup.lastHeapUpdateLiveRouteLookupForTesting();
        require(deleteLookup.isPresent() && deleteLookup.get().isDefaultStorageProvider(),
                "Expected N3 DELETE lookup to be default-provider heap only");
        require(updateLookup.isPresent() && updateLookup.get().isDefaultStorageProvider(),
                "Expected N3 UPDATE lookup to be default-provider heap only");
    }

    private static void assertValue(Statement statement, String table, int id, String value)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT value FROM APP." + table + " WHERE id = " + id)) {
            require(rows.next(), "Expected row " + id + " in " + table);
            require(value.equals(rows.getString(1)), "Expected value " + value + " for row " + id);
            require(!rows.next(), "Expected one row " + id + " in " + table);
        }
    }

    private static void assertMissingId(Statement statement, String table, int id)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT id FROM APP." + table + " WHERE id = " + id)) {
            require(!rows.next(), "Expected row " + id + " to be deleted from " + table);
        }
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_DELETE_UPDATE_LIVE_ROUTE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_INSERT_LIVE_ROUTE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
