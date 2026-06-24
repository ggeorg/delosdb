package delosdb.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableCostEstimate;
import org.apache.derby.iapi.store.types.DelosTableGuarantee;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.impl.services.storetypes.EngineHeapDerbyAccessSupport;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccess;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

/**
 * O5 full provider-parity closeout proof.
 *
 * <p>O5 proves the final architecture claim for the supported provider surface:
 * delos_mvcc and heap both have live scan/insert/update/delete execution routes,
 * and both expose cost-capable table access. Heap transaction and locking
 * semantics remain Derby-owned; O5 deliberately does not claim MVCC-style heap
 * reservation or a provider-neutral tryLock API.</p>
 */
public final class StoragePhaseO5FullProviderParityCloseoutSmoke {
    private static final String DATABASE_PATH = "storage-phase-o5-full-provider-parity-closeout-db";
    private static final String MVCC_TABLE = "O5_MVCC_PROVIDER";
    private static final String HEAP_TABLE = "O5_HEAP_PROVIDER";

    private StoragePhaseO5FullProviderParityCloseoutSmoke() {
    }

    public static void main(String[] args) throws Exception {
        proveProofOnlyHeapSourceIsGone();
        proveHeapFacadeAdvertisesHonestCostAndGuarantees();
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveTwoLiveProvidersUnderTheCloseoutGate();
        } finally {
            clearProofProperties();
            resetCounters();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_o5_full_provider_parity_closeout: PASS");
    }

    private static void proveProofOnlyHeapSourceIsGone() {
        require(!Files.exists(Path.of(
                        "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessProof.java")),
                "O5 requires the stale proof-only EngineHeapTableAccessProof.java source to be deleted");
    }

    private static void proveHeapFacadeAdvertisesHonestCostAndGuarantees() {
        EngineHeapTableAccess heapAccess = new EngineHeapTableAccess(
                DelosTableIdentity.of("APP", "O5_HEAP_COST"),
                DelosTableShape.of(List.of(
                        new DelosTableShape.Column("ID", "INTEGER", false),
                        new DelosTableShape.Column("VALUE", "VARCHAR", true))));

        require(heapAccess.capabilities().supports(DelosTableCapability.FILTERABLE),
                "Expected heap facade to advertise filterable scan capability");
        require(heapAccess.capabilities().supports(DelosTableCapability.COSTABLE),
                "Expected heap facade to advertise cost capability");
        require(heapAccess.guarantees().contains(DelosTableGuarantee.ROW_LOCKING),
                "Expected heap facade to advertise Derby-owned row-locking guarantee");
        require(heapAccess.guarantees().contains(DelosTableGuarantee.DURABLE_RECOVERY_LOG),
                "Expected heap facade to advertise Derby durable recovery-log guarantee");
        require(!heapAccess.guarantees().contains(DelosTableGuarantee.SNAPSHOT_ISOLATION),
                "Heap must not claim MVCC snapshot isolation");

        DelosTableCostEstimate estimate = heapAccess.estimateTableCost(DelosAccessContext.builder(true)
                .put(EngineHeapDerbyAccessSupport.ESTIMATED_ROW_COUNT_KEY, 4L)
                .put(EngineHeapDerbyAccessSupport.ESTIMATED_SCAN_COST_KEY, 9.0d)
                .build());
        require(estimate.logicalRowCount() == 4L,
                "Expected heap facade cost mapping to preserve logical row count");
        require(estimate.visibleRowCount() == 4L,
                "Expected heap facade cost mapping to preserve visible row count");
        require(estimate.estimatedFullScanCost() == 9L,
                "Expected heap facade cost mapping to preserve estimated scan cost");
    }

    private static void proveTwoLiveProvidersUnderTheCloseoutGate() throws Exception {
        clearProofProperties();
        resetCounters();
        enableMvccNativeRoute();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_PROVIDER_PARITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected delos_mvcc table creation for O5 closeout");
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected ordinary heap table creation for O5 closeout");

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
                "Expected O5 to prove live delos_mvcc provider route");
        require(DelosTableScanProviderLookup.heapSelectLiveRouteBranchCountForTesting() >= 1,
                "Expected O5 heap SELECT live route to be reached");
        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() >= 2,
                "Expected O5 heap INSERT live route to be reached");
        require(DelosTableScanProviderLookup.heapUpdateLiveRouteBranchCountForTesting() >= 1,
                "Expected O5 heap UPDATE live route to be reached");
        require(DelosTableScanProviderLookup.heapDeleteLiveRouteBranchCountForTesting() >= 1,
                "Expected O5 heap DELETE live route to be reached");
        require(EngineHeapTableAccess.facadeScanOpenCountForTesting() >= 1,
                "Expected O5 heap SELECT to pass through EngineHeapTableAccess facade");
        require(EngineHeapTableAccess.facadeMutationAdapterOpenCountForTesting() >= 2,
                "Expected O5 heap INSERT to pass through EngineHeapTableAccess mutation facade");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapSelectLiveRouteLookupForTesting(),
                "Expected O5 heap SELECT lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapInsertLiveRouteLookupForTesting(),
                "Expected O5 heap INSERT lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapUpdateLiveRouteLookupForTesting(),
                "Expected O5 heap UPDATE lookup to be default-provider heap");
        assertDefaultProvider(DelosTableScanProviderLookup.lastHeapDeleteLiveRouteLookupForTesting(),
                "Expected O5 heap DELETE lookup to be default-provider heap");
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
