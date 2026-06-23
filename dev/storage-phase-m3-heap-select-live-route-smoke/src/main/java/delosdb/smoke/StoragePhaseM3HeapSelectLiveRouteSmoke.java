package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * M3 heap SELECT live route proof.
 *
 * <p>This smoke proves the first supported-shape heap SELECT route through a
 * Delos table-access object while keeping heap mutation, locking, reservation,
 * and registry parity out of scope.</p>
 */
public final class StoragePhaseM3HeapSelectLiveRouteSmoke {
    private static final String DATABASE_PATH = "storage-phase-m3-heap-select-live-route-db";
    private static final String HEAP_OFF_TABLE = "M3_HEAP_OFF";
    private static final String HEAP_LIVE_TABLE = "M3_HEAP_LIVE";
    private static final String MVCC_TABLE = "M3_MVCC";

    private StoragePhaseM3HeapSelectLiveRouteSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveDecisionDocument();
            proveSourceShape();
            proveLiveRouteDisabledLeavesHeapOnDerbyRoute();
            proveLiveRouteEnabledReadsHeapRows();
            proveDelosMvccRouteWinsBeforeHeapLiveRoute();
            proveNoHeapMutationLockingOrRegistryParityClaim();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            DelosTableScanProviderLookup.resetHeapScanShadowForTesting();
            DelosTableScanProviderLookup.resetHeapSelectLiveRouteForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_m3_heap_select_live_route: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-m3-heap-select-live-route.md"), List.of(
                "Storage Phase M3 — Heap SELECT live route for supported shapes",
                "delosdb.storage.phaseM3.heapSelectLiveRoute=true",
                "DelosHeapLiveTableScanResultSet",
                "EngineHeapTableAccessLiveCandidate.scan(...)",
                "Unsupported heap shapes still fall back to Derby's normal route",
                "heap INSERT / DELETE / UPDATE live route",
                "no heap lock/reservation API appears"));
    }

    private static void proveSourceShape() throws Exception {
        Path factory = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java");
        String factorySource = readSource(factory);
        require(factorySource.contains("DelosTableScanResultSet.createIfEnabled(params)"),
                "Expected delos_mvcc table-scan branch to remain first");
        require(factorySource.contains("DelosHeapLiveTableScanResultSet.createIfEnabled(params)"),
                "Expected M3 heap live branch at GenericResultSetFactory table-scan seam");
        require(factorySource.contains("DelosHeapScanShadowResultSet.createIfEnabled(params)"),
                "Expected M2 heap shadow branch to remain available behind its own flag");
        require(factorySource.indexOf("DelosTableScanResultSet.createIfEnabled(params)")
                        < factorySource.indexOf("DelosHeapLiveTableScanResultSet.createIfEnabled(params)"),
                "Expected delos_mvcc branch to be tested before heap live branch");
        require(factorySource.indexOf("DelosHeapLiveTableScanResultSet.createIfEnabled(params)")
                        < factorySource.indexOf("DelosHeapScanShadowResultSet.createIfEnabled(params)"),
                "Expected heap live branch to be tested before heap shadow branch");
        require(factorySource.indexOf("DelosHeapLiveTableScanResultSet.createIfEnabled(params)")
                        < factorySource.indexOf("return new TableScanResultSet(params)"),
                "Expected heap live branch to fall back to ordinary TableScanResultSet");
        int bulkStart = factorySource.indexOf("getBulkTableScanResultSet");
        int bulkLive = factorySource.indexOf("DelosHeapLiveTableScanResultSet.createIfEnabled(params)", bulkStart);
        int bulkFallback = factorySource.indexOf("return new BulkTableScanResultSet(params, rowsPerRead, disableForHoldable)", bulkStart);
        require(bulkStart >= 0 && bulkLive > bulkStart && bulkLive < bulkFallback,
                "Expected heap live branch in the bulk table-scan factory path before BulkTableScanResultSet fallback");

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapLiveTableScanResultSet.java"), List.of(
                "M3 property-gated heap SELECT live route",
                "HEAP_SELECT_LIVE_ROUTE_PROPERTY",
                "delosdb.storage.phaseM3.heapSelectLiveRoute",
                "EngineHeapTableAccessLiveCandidate",
                "heapAccess.scan(heapContext(transactionController), List.of(), DelosProjection.all())",
                "!params.forUpdate",
                "!hasIndexName(params.indexName)",
                "params.indexColItem == -1",
                "params.startKeyGetter == null",
                "params.stopKeyGetter == null",
                "resetForTesting",
                "liveBranchCountForTesting",
                "lastLiveLookupForTesting"));
        assertSourceDoesNotContain(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapLiveTableScanResultSet.java"), List.of(
                "DelosNativeTableRegistry",
                "DelosMutableTableAccess",
                "DelosMvccReservableTableAccess",
                "reserveMutation(",
                "RowChangerImpl",
                "ConglomerateController"));
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessLiveCandidate.java"), List.of(
                "M1 isolated heap scan candidate",
                "HOLD_SCAN_OPEN_KEY",
                "holdScanOpen(context)",
                "TransactionController#openScan",
                "TransactionController#openCompiledScan",
                "scanController.fetchNext(fetchRow)",
                "scanController.fetchLocation(rowLocation)"));
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanProviderLookup.java"), List.of(
                "FACTORY_HEAP_SELECT_LIVE_ROUTE_PROPERTY",
                "resetHeapSelectLiveRouteForTesting",
                "heapSelectLiveRouteBranchCountForTesting",
                "lastHeapSelectLiveRouteLookupForTesting"));
    }

    private static void proveLiveRouteDisabledLeavesHeapOnDerbyRoute() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        DelosTableScanProviderLookup.resetHeapScanShadowForTesting();
        DelosTableScanProviderLookup.resetHeapSelectLiveRouteForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_OFF_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected ordinary heap table creation to stay Derby-owned");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_OFF_TABLE + " VALUES (?, ?)", 1, "off") == 1,
                    "Expected ordinary heap INSERT to stay Derby-owned");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             PreparedStatement select = connection.prepareStatement(
                     "SELECT value FROM APP." + HEAP_OFF_TABLE + " WHERE id = ?")) {
            select.setInt(1, 1);
            try (ResultSet rows = select.executeQuery()) {
                require(rows.next(), "Expected heap SELECT to return row with M3 flag disabled");
                require("off".equals(rows.getString(1)), "Expected Derby-owned heap row with M3 flag disabled");
                require(!rows.next(), "Expected one heap row with M3 flag disabled");
            }
        }

        Optional<DelosTableScanProviderLookup.Result> observed =
                DelosTableScanProviderLookup.lastFactoryLookupForTesting();
        require(observed.isPresent(), "Expected provider lookup observation for disabled-M3 heap SELECT");
        require(observed.get().isDefaultStorageProvider(),
                "Expected disabled-M3 heap table to resolve as default provider");
        require(DelosTableScanProviderLookup.heapSelectLiveRouteBranchCountForTesting() == 0,
                "Expected heap live route not to run when the M3 property is disabled");
        require(DelosTableScanProviderLookup.heapScanShadowBranchCountForTesting() == 0,
                "Expected heap shadow route not to run when the M2 property is disabled");
    }

    private static void proveLiveRouteEnabledReadsHeapRows() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        DelosTableScanProviderLookup.resetHeapScanShadowForTesting();
        DelosTableScanProviderLookup.resetHeapSelectLiveRouteForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_SELECT_LIVE_ROUTE_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_LIVE_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected M3 heap table creation to stay Derby-owned");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_LIVE_TABLE + " VALUES (?, ?)", 2, "two") == 1,
                    "Expected ordinary heap INSERT to stay Derby-owned under M3");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_LIVE_TABLE + " VALUES (?, ?)", 3, "three") == 1,
                    "Expected second ordinary heap INSERT to stay Derby-owned under M3");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             PreparedStatement select = connection.prepareStatement(
                     "SELECT value FROM APP." + HEAP_LIVE_TABLE + " WHERE id = ?")) {
            select.setInt(1, 3);
            try (ResultSet rows = select.executeQuery()) {
                require(rows.next(), "Expected M3 heap live SELECT to return filtered row");
                require("three".equals(rows.getString(1)), "Expected M3 heap live SELECT to preserve projection");
                require(!rows.next(), "Expected one filtered M3 heap row");
            }
        }

        require(DelosTableScanProviderLookup.heapSelectLiveRouteBranchCountForTesting() > 0,
                "Expected property-gated heap live route to be reached for supported heap SELECT");
        Optional<DelosTableScanProviderLookup.Result> liveLookup =
                DelosTableScanProviderLookup.lastHeapSelectLiveRouteLookupForTesting();
        require(liveLookup.isPresent(), "Expected recorded M3 heap live provider lookup");
        require(liveLookup.get().isDefaultStorageProvider(),
                "Expected M3 heap live lookup to be default-provider only");
        require(DelosTableScanProviderLookup.heapScanShadowBranchCountForTesting() == 0,
                "Expected M3 heap live route not to depend on M2 shadow route");
        require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_LIVE_TABLE),
                "Expected M3 heap live table not to be registered as a Delos native table");
    }

    private static void proveDelosMvccRouteWinsBeforeHeapLiveRoute() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        DelosTableScanProviderLookup.resetHeapScanShadowForTesting();
        DelosTableScanProviderLookup.resetHeapSelectLiveRouteForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_SELECT_LIVE_ROUTE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected explicit delos_mvcc CREATE TABLE to register native provider table");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?)", 4, "mvcc") == 1,
                    "Expected delos_mvcc INSERT to use native provider path");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             PreparedStatement select = connection.prepareStatement(
                     "SELECT value FROM APP." + MVCC_TABLE + " WHERE id = ?")) {
            select.setInt(1, 4);
            try (ResultSet rows = select.executeQuery()) {
                require(rows.next(), "Expected delos_mvcc SELECT to return native row under M3");
                require("mvcc".equals(rows.getString(1)), "Expected delos_mvcc row from native route under M3");
                require(!rows.next(), "Expected one delos_mvcc row under M3");
            }
        }

        Optional<DelosTableScanProviderLookup.Result> observed =
                DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting();
        require(observed.isPresent(), "Expected non-default provider lookup for delos_mvcc SELECT under M3");
        require(observed.get().isProvider("delos_mvcc"),
                "Expected delos_mvcc SELECT to resolve before heap live branch");
        require(DelosTableScanProviderLookup.heapSelectLiveRouteBranchCountForTesting() == 0,
                "Expected delos_mvcc SELECT not to reach heap live route");
        require(DelosTableScanProviderLookup.heapScanShadowBranchCountForTesting() == 0,
                "Expected delos_mvcc SELECT not to reach heap shadow route");
        require(DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                "Expected delos_mvcc table to remain registered in native registry");
    }

    private static void proveNoHeapMutationLockingOrRegistryParityClaim() throws Exception {
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "PROVIDER_NAME = \"delos_mvcc\"",
                "isDelosMvcc",
                "EngineMvccTableAccess"));
        assertSourceDoesNotContain(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "EngineHeapTableAccessProof",
                "EngineHeapTableAccessLiveCandidate",
                "PROVIDER_NAME = \"heap\""));
        assertSourceContains(Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/InsertResultSet.java"), List.of(
                "rowChanger.insertRow(row, false)",
                "rowChanger.insertRow(row, true)"));
        assertSourceContains(Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DeleteResultSet.java"), List.of(
                "rc.deleteRow(row,baseRowLocation)"));
        assertSourceContains(Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/UpdateResultSet.java"), List.of(
                "rowChanger.updateRow(row,newBaseRow,baseRowLocation)"));
        assertSourceDoesNotContain(Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutableTableAccess.java"), List.of(
                "tryLock(",
                "reserveMutation("));
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_SKELETON_BRANCH_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_SCAN_SHADOW_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_SELECT_LIVE_ROUTE_PROPERTY);
    }

    private static String readSource(Path sourceFile) throws Exception {
        require(Files.exists(sourceFile), "Missing expected source file: " + sourceFile);
        return Files.readString(sourceFile);
    }

    private static void assertSourceContains(Path sourceFile, List<String> requiredMarkers) throws Exception {
        String text = readSource(sourceFile);
        for (String marker : requiredMarkers) {
            require(text.contains(marker), sourceFile + " is missing required M3 marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path sourceFile, List<String> forbiddenMarkers) throws Exception {
        String text = readSource(sourceFile);
        for (String marker : forbiddenMarkers) {
            require(!text.contains(marker), sourceFile + " contains forbidden M3 marker: " + marker);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
