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
 * K1 heap live-provider feasibility gate.
 *
 * <p>This smoke does not activate heap under Delos. It freezes the current
 * source truth after source inspection: delos_mvcc is the only live Delos
 * native provider, ordinary heap SQL remains Derby-native, the heap adapter is
 * still proof-only, and heap mutation/locking parity remains deferred.</p>
 */
public final class StoragePhaseK1HeapLiveProviderFeasibilitySmoke {
    private static final String DATABASE_PATH = "storage-phase-k1-heap-live-provider-feasibility-db";
    private static final String HEAP_TABLE = "K1_HEAP";
    private static final String MVCC_TABLE = "K1_MVCC";

    private StoragePhaseK1HeapLiveProviderFeasibilitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveDecisionDocumentRecordsSourceBackedALiteAnswer();
            proveBridgeFilesRemainAbsentFromActiveExecution();
            proveGenericResultSetFactoryRemainsScanBranchPoint();
            proveHeapDefaultProviderSelectStillUsesDerbyNativeRoute();
            proveDelosMvccSelectStillUsesNativeRoute();
            proveHeapMutationsStillUseRowChangerAndConglomerateController();
            proveEngineHeapTableAccessProofRemainsProofOnly();
            proveNoLiveHeapDelosRoutingHasAppeared();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_k1_heap_live_provider_feasibility: PASS");
    }

    private static void proveDecisionDocumentRecordsSourceBackedALiteAnswer() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-k1-heap-live-provider-feasibility.md"), List.of(
                "Answer A-lite",
                "Heap scan/cost live-provider parity is incrementally feasible",
                "Heap mutation and locking parity should be deferred",
                "L1 should stay MVCC-specific",
                "No heap routing change",
                "GenericResultSetFactory.java",
                "TableScanResultSet.java",
                "RowChangerImpl.java",
                "EngineHeapTableAccessProof.java",
                "B2IRowLocking3.java",
                "postgres-master/src/include/access/tableam.h",
                "server-main/sql/handler.h",
                "apache-calcite-1.42.0-src/core/src/main/java/org/apache/calcite/schema/FilterableTable.java"));
    }

    private static void proveBridgeFilesRemainAbsentFromActiveExecution() throws Exception {
        assertPathAbsent(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/VersionedStorageSqlBridge.java"));
        assertPathAbsent(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/VersionedStorageSqlResult.java"));

        for (Path sourceFile : List.of(
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosNativeResultSetSupport.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosInsertResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosDeleteResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosUpdateResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/jdbc/EmbedStatement.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/jdbc/EmbedConnection.java"))) {
            assertSourceDoesNotContain(sourceFile, List.of("VersionedStorageSqlBridge"));
        }
    }

    private static void proveGenericResultSetFactoryRemainsScanBranchPoint() throws Exception {
        Path factory = Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java");
        assertSourceContains(factory, List.of(
                "public NoPutResultSet getTableScanResultSet(",
                "DelosTableScanProviderLookup.observeFactoryLookupIfEnabled",
                "TableScanResultSetParameters params = tableScanParameters(",
                "DelosTableScanResultSet.createIfEnabled(params)",
                "if (delosTableScan != null) { return delosTableScan; }",
                "return new TableScanResultSet(params);",
                "return new BulkTableScanResultSet(params, rowsPerRead, disableForHoldable);"));
    }

    private static void proveHeapDefaultProviderSelectStillUsesDerbyNativeRoute() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected ordinary heap CREATE TABLE to use Derby's default path");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_TABLE + " VALUES (?, ?)", 1, "heap") == 1,
                    "Expected ordinary heap INSERT to use Derby's default path");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             PreparedStatement select = connection.prepareStatement(
                     "SELECT value FROM APP." + HEAP_TABLE + " WHERE id = ?")) {
            select.setInt(1, 1);
            try (ResultSet rows = select.executeQuery()) {
                require(rows.next(), "Expected ordinary heap SELECT to return the inserted row");
                require("heap".equals(rows.getString(1)), "Expected heap row value from Derby default path");
                require(!rows.next(), "Expected one ordinary heap row");
            }
        }

        Optional<DelosTableScanProviderLookup.Result> observed = DelosTableScanProviderLookup.lastFactoryLookupForTesting();
        require(observed.isPresent(), "Expected provider lookup observation for ordinary heap SELECT");
        require(observed.get().isDefaultStorageProvider(),
                "Expected ordinary heap table to resolve as Derby default provider");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isEmpty(),
                "Expected ordinary heap SELECT not to be observed as a non-default native provider");
        require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_TABLE),
                "Expected ordinary heap table not to be registered in DelosNativeTableRegistry");
    }

    private static void proveDelosMvccSelectStillUsesNativeRoute() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected explicit delos_mvcc CREATE TABLE to register a native provider table");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?)", 2, "mvcc") == 1,
                    "Expected explicit delos_mvcc INSERT to use live native provider path");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             PreparedStatement select = connection.prepareStatement(
                     "SELECT value FROM APP." + MVCC_TABLE + " WHERE id = ?")) {
            select.setInt(1, 2);
            try (ResultSet rows = select.executeQuery()) {
                require(rows.next(), "Expected delos_mvcc SELECT to return the inserted row");
                require("mvcc".equals(rows.getString(1)), "Expected mvcc row value from native provider path");
                require(!rows.next(), "Expected one delos_mvcc row");
            }
        }

        Optional<DelosTableScanProviderLookup.Result> observed = DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting();
        require(observed.isPresent(), "Expected non-default provider lookup observation for delos_mvcc SELECT");
        require(observed.get().isProvider("delos_mvcc"), "Expected explicit delos_mvcc table to resolve as delos_mvcc");
        require(DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                "Expected delos_mvcc table to be registered in DelosNativeTableRegistry");
        require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_TABLE),
                "Expected heap table to remain outside DelosNativeTableRegistry while K1 only decides feasibility");
    }

    private static void proveHeapMutationsStillUseRowChangerAndConglomerateController() throws Exception {
        assertSourceContains(Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/InsertResultSet.java"), List.of(
                "getRowChanger(",
                "rowChanger.open(lockMode)",
                "rowChanger.insertRow(row, false)",
                "rowChanger.insertRow(row, true)"));
        assertSourceContains(Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DeleteResultSet.java"), List.of(
                "getRowChanger(",
                "rc.open(lockMode)",
                "rc.deleteRow(row,baseRowLocation)",
                "rc.deleteRow(deferredBaseRow, baseRowLocation)"));
        assertSourceContains(Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/UpdateResultSet.java"), List.of(
                "getRowChanger(",
                "rowChanger.open(lockMode)",
                "rowChanger.updateRow(row,newBaseRow,baseRowLocation)"));
        assertSourceContains(Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java"), List.of(
                "tc.openCompiledConglomerate(",
                "tc.openConglomerate(",
                "baseCC.insertAndFetchLocation",
                "baseCC.insert(baseRow.getRowArray())",
                "baseCC.delete(baseRowLocation)",
                "baseCC.replace(baseRowLocation"));
    }

    private static void proveEngineHeapTableAccessProofRemainsProofOnly() throws Exception {
        Path heapProof = Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessProof.java");
        assertSourceContains(heapProof, List.of(
                "proof adapter only",
                "throw proofOnlyUnsupported",
                "Derby heap scan still runs through TableScanResultSet",
                "Derby heap INSERT still runs through RowChangerImpl",
                "Derby heap UPDATE still runs through RowChangerImpl",
                "Derby heap DELETE still runs through RowChangerImpl",
                "HeapRowIdentity",
                "EngineStoreRowLocationBridge.requireStoreRowLocation",
                "compileTimeOpenHeapScan",
                "compileTimeOpenCompiledHeapScan",
                "compileTimeOpenHeapConglomerate",
                "compileTimeInsertAndFetchHeapRowLocation"));

        // Later M-phase work may add an isolated heap scan candidate class.
        // K1's closed truth is that heap SQL routing remains Derby-native;
        // the routing guard below, not this proof adapter check, owns that invariant.
    }

    private static void proveNoLiveHeapDelosRoutingHasAppeared() throws Exception {
        assertSourceContains(Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"), List.of(
                "lookup.isEmpty() || lookup.get().isDefaultStorageProvider()",
                "return Optional.empty()",
                "openNativeTableAccess",
                "EngineMvccTableAccess"));
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "PROVIDER_NAME = \"delos_mvcc\"",
                "isDelosMvcc",
                "EngineMvccTableAccess"));
        assertSourceDoesNotContain(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "EngineHeapTableAccessProof",
                "PROVIDER_NAME = \"heap\""));

        for (Path sourceFile : List.of(
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/FromBaseTable.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosInsertResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosDeleteResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosUpdateResultSet.java"))) {
            assertSourceDoesNotContain(sourceFile, List.of(
                    "EngineHeapTableAccessProof",
                    "heapScanShadow",
                    "EngineHeapTableAccessLive"));
        }
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_SKELETON_BRANCH_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_HEAP_SCAN_SHADOW_PROPERTY);
    }

    private static String readSource(Path sourceFile) throws Exception {
        require(Files.exists(sourceFile), "Missing expected source file: " + sourceFile);
        return Files.readString(sourceFile);
    }

    private static void assertPathAbsent(Path path) {
        require(!Files.exists(path), "Expected obsolete bridge/source file to remain absent: " + path);
    }

    private static void assertSourceContains(Path sourceFile, List<String> requiredMarkers) throws Exception {
        String text = readSource(sourceFile);
        for (String marker : requiredMarkers) {
            require(text.contains(marker), sourceFile + " is missing required K1 marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path sourceFile, List<String> forbiddenMarkers) throws Exception {
        String text = readSource(sourceFile);
        for (String marker : forbiddenMarkers) {
            require(!text.contains(marker), sourceFile + " contains forbidden K1 marker: " + marker);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
