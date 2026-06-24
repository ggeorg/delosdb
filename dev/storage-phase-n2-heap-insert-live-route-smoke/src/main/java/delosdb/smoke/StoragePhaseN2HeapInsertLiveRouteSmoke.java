package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

/**
 * N2 heap INSERT live-route proof.
 */
public final class StoragePhaseN2HeapInsertLiveRouteSmoke {
    private static final String DATABASE_PATH = "storage-phase-n2-heap-insert-live-route-db";
    private static final String HEAP_OFF_TABLE = "N2_HEAP_INSERT_OFF";
    private static final String HEAP_LIVE_TABLE = "N2_HEAP_INSERT_LIVE";
    private static final String HEAP_UNSUPPORTED_TABLE = "N2_HEAP_INSERT_UNSUPPORTED";
    private static final String MVCC_TABLE = "N2_MVCC_INSERT";

    private StoragePhaseN2HeapInsertLiveRouteSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveDecisionDocument();
            proveSourceShape();
            proveDisabledLeavesHeapInsertOnDerbyRoute();
            proveEnabledRoutesSupportedHeapInsert();
            proveUnsupportedHeapInsertFallsBackToDerby();
            proveDelosMvccInsertWinsBeforeHeapInsertRoute();
            proveNoDeleteUpdateLockingOrMutableProviderClaim();
            proveGradleWiring();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            DelosTableScanProviderLookup.resetHeapInsertLiveRouteForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_n2_heap_insert_live_route: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-n2-heap-insert-live-route.md"), List.of(
                "Storage Phase N2 — Heap INSERT live route",
                "delosdb.storage.phaseN2.heapInsertLiveRoute=true",
                "EngineHeapRowChangerMutationAdapter",
                "Unsupported shapes fall back to ordinary Derby `InsertResultSet`",
                "No heap DELETE live route",
                "No heap UPDATE live route",
                "No generic DelosMutableTableAccess.tryLock(...)",
                "N3 — heap DELETE / UPDATE live path"));
    }

    private static void proveSourceShape() throws Exception {
        Path heapInsert = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapInsertResultSet.java");
        assertSourceContains(heapInsert, List.of(
                "N2 property-gated heap INSERT live route",
                "HEAP_INSERT_LIVE_ROUTE_PROPERTY",
                "delosdb.storage.phaseN2.heapInsertLiveRoute",
                "EngineHeapRowChangerMutationAdapter.open",
                "adapter.insert(row)",
                "adapter.finish()",
                "isSupportedHeapInsertShape",
                "lookup.get().isDefaultStorageProvider()",
                "params.generationClauses == null",
                "params.checkGM == null",
                "!constants.deferred",
                "constants.getFKInfo() == null",
                "constants.getTriggerInfo() == null",
                "!constants.hasAutoincrement()",
                "constants.getProperty(\"insertMode\") == null",
                "resetForTesting",
                "liveBranchCountForTesting",
                "lastLiveLookupForTesting"));
        assertSourceNotContains(heapInsert, List.of(
                "DelosHeapDeleteResultSet",
                "DelosHeapUpdateResultSet",
                "tryLock(",
                "reserveMutation(",
                "implements DelosMutableTableAccess"));

        Path factory = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java");
        String factorySource = Files.readString(factory);
        require(factorySource.contains("DelosInsertResultSet.createIfEnabled(params)"),
                "Expected delos_mvcc INSERT branch to remain present");
        require(factorySource.contains("DelosHeapInsertResultSet.createIfEnabled(params)"),
                "Expected N2 heap INSERT branch at GenericResultSetFactory insert seam");
        require(factorySource.indexOf("DelosInsertResultSet.createIfEnabled(params)")
                        < factorySource.indexOf("DelosHeapInsertResultSet.createIfEnabled(params)"),
                "Expected delos_mvcc INSERT route to be tested before heap INSERT route");
        require(factorySource.indexOf("DelosHeapInsertResultSet.createIfEnabled(params)")
                        < factorySource.indexOf("return new InsertResultSet(params)"),
                "Expected heap INSERT live route to fall back to ordinary InsertResultSet");

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanProviderLookup.java"), List.of(
                "FACTORY_HEAP_INSERT_LIVE_ROUTE_PROPERTY",
                "resetHeapInsertLiveRouteForTesting",
                "heapInsertLiveRouteBranchCountForTesting",
                "lastHeapInsertLiveRouteLookupForTesting"));
    }

    private static void proveDisabledLeavesHeapInsertOnDerbyRoute() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        DelosTableScanProviderLookup.resetHeapInsertLiveRouteForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_OFF_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected heap table creation to stay Derby-owned");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_OFF_TABLE + " VALUES (?, ?)", 1, "off") == 1,
                    "Expected heap INSERT to work with N2 flag disabled");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertSingleValue(statement, HEAP_OFF_TABLE, 1, "off");
        }
        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() == 0,
                "Expected heap INSERT live route not to run when N2 property is disabled");
    }

    private static void proveEnabledRoutesSupportedHeapInsert() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        DelosTableScanProviderLookup.resetHeapInsertLiveRouteForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_INSERT_LIVE_ROUTE_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_LIVE_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected heap table creation to stay Derby-owned");
            require(statement.executeUpdate(
                    "CREATE INDEX N2_HEAP_INSERT_LIVE_IX ON APP." + HEAP_LIVE_TABLE + "(id)") == 0,
                    "Expected Derby index creation to stay Derby-owned");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_LIVE_TABLE + " VALUES (?, ?)", 2, "two") == 1,
                    "Expected supported heap INSERT to work through N2 live route");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_LIVE_TABLE + " VALUES (?, ?)", 3, "three") == 1,
                    "Expected second supported heap INSERT to work through N2 live route");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertSingleValue(statement, HEAP_LIVE_TABLE, 2, "two");
            assertSingleValue(statement, HEAP_LIVE_TABLE, 3, "three");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM APP." + HEAP_LIVE_TABLE)) {
                require(rows.next(), "Expected count row after N2 heap insert route");
                require(rows.getInt(1) == 2, "Expected exactly two N2 heap-inserted rows");
            }
        }

        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() >= 2,
                "Expected property-gated heap INSERT live route to be reached for supported heap INSERT");
        Optional<DelosTableScanProviderLookup.Result> liveLookup =
                DelosTableScanProviderLookup.lastHeapInsertLiveRouteLookupForTesting();
        require(liveLookup.isPresent(), "Expected recorded N2 heap INSERT provider lookup");
        require(liveLookup.get().isDefaultStorageProvider(),
                "Expected N2 heap INSERT lookup to be default-provider only");
        require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_LIVE_TABLE),
                "Expected N2 heap INSERT table not to be registered as a delos_mvcc native table");
    }

    private static void proveUnsupportedHeapInsertFallsBackToDerby() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetHeapInsertLiveRouteForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_INSERT_LIVE_ROUTE_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_UNSUPPORTED_TABLE
                            + " (id INT GENERATED BY DEFAULT AS IDENTITY, value VARCHAR(32))") == 0,
                    "Expected unsupported autoincrement heap table creation to stay Derby-owned");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_UNSUPPORTED_TABLE + "(value) VALUES (?)", "fallback") == 1,
                    "Expected autoincrement heap INSERT to fall back to Derby InsertResultSet");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT value FROM APP." + HEAP_UNSUPPORTED_TABLE + " WHERE value = 'fallback'")) {
                require(rows.next(), "Expected fallback autoincrement row");
                require("fallback".equals(rows.getString(1)), "Expected fallback autoincrement value");
                require(!rows.next(), "Expected one fallback autoincrement row");
            }
        }
        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() == 0,
                "Expected unsupported autoincrement INSERT not to reach N2 heap live route");
    }

    private static void proveDelosMvccInsertWinsBeforeHeapInsertRoute() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetHeapInsertLiveRouteForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_INSERT_LIVE_ROUTE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected delos_mvcc table creation to stay parser/catalog-owned");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?)", 5, "mvcc") == 1,
                    "Expected delos_mvcc INSERT to remain native before heap INSERT branch");
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT value FROM APP." + MVCC_TABLE + " WHERE id = ?")) {
                select.setInt(1, 5);
                try (ResultSet rows = select.executeQuery()) {
                    require(rows.next(), "Expected delos_mvcc row after native INSERT");
                    require("mvcc".equals(rows.getString(1)), "Expected delos_mvcc INSERT value");
                    require(!rows.next(), "Expected one delos_mvcc row");
                }
            }
        }
        require(DelosTableScanProviderLookup.heapInsertLiveRouteBranchCountForTesting() == 0,
                "Expected delos_mvcc INSERT not to route through heap INSERT live branch");
    }

    private static void proveNoDeleteUpdateLockingOrMutableProviderClaim() throws Exception {
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapDeleteResultSet.java"));
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapUpdateResultSet.java"));
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapMutableTableAccess.java"));

        Path factory = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java");
        assertSourceNotContains(factory, List.of(
                "DelosHeapDeleteResultSet",
                "DelosHeapUpdateResultSet"));

        Path kernel = Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutableTableAccess.java");
        assertSourceNotContains(kernel, List.of(
                "tryLock(",
                "reserveMutation("));
    }

    private static void proveGradleWiring() throws Exception {
        assertSourceContains(Path.of("gradle/storage-native-execution-closeout.gradle"), List.of(
                "storage-phase-n2-heap-insert-live-route-smoke",
                "compileStoragePhaseN2HeapInsertLiveRouteSmoke",
                "storagePhaseN2HeapInsertLiveRouteSmoke",
                "verifyStoragePhaseN2HeapInsertLiveRoute",
                "verifyStoragePhaseN16HeapMutationClassificationDecision",
                "StoragePhaseN2HeapInsertLiveRouteSmoke",
                "through N2"));
        assertSourceContains(Path.of("gradle/delosdb-permanent-storage-guards.gradle"), List.of(
                "through N2 heap INSERT live route"));
    }

    private static void assertSingleValue(Statement statement, String table, int id, String value)
            throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT value FROM APP." + table + " WHERE id = " + id)) {
            require(rows.next(), "Expected row " + id + " in " + table);
            require(value.equals(rows.getString(1)), "Expected value " + value + " for row " + id);
            require(!rows.next(), "Expected one row " + id + " in " + table);
        }
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_INSERT_LIVE_ROUTE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_SELECT_LIVE_ROUTE_PROPERTY);
    }

    private static void assertPathMissing(Path path) {
        require(!Files.exists(path), path + " must not exist in N2");
    }

    private static void assertSourceContains(Path path, List<String> markers) throws Exception {
        String source = Files.readString(path);
        for (String marker : markers) {
            require(source.contains(marker), path + " is missing required N2 marker: " + marker);
        }
    }

    private static void assertSourceNotContains(Path path, List<String> forbidden) throws Exception {
        String source = Files.readString(path);
        for (String marker : forbidden) {
            require(!source.contains(marker), path + " must not contain N2-forbidden marker: " + marker);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
