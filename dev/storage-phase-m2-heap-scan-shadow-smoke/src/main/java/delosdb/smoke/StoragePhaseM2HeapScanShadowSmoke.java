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
 * M2 heap scan shadow branch proof.
 *
 * <p>This smoke proves the new heap branch is property-gated, default-provider
 * only, and still Derby-owned.  It also proves delos_mvcc continues to use the
 * native Delos table-scan route when both flags are enabled.</p>
 */
public final class StoragePhaseM2HeapScanShadowSmoke {
    private static final String DATABASE_PATH = "storage-phase-m2-heap-scan-shadow-db";
    private static final String HEAP_OFF_TABLE = "M2_HEAP_OFF";
    private static final String HEAP_ON_TABLE = "M2_HEAP_ON";
    private static final String MVCC_TABLE = "M2_MVCC";

    private StoragePhaseM2HeapScanShadowSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveDecisionDocument();
            proveSourceShape();
            proveShadowDisabledLeavesHeapOnDerbyRoute();
            proveShadowEnabledReachesHeapShadowBranch();
            proveDelosMvccDoesNotUseHeapShadowBranch();
            proveNoHeapProviderActivationOrMutationClaim();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            DelosTableScanProviderLookup.resetHeapScanShadowForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_m2_heap_scan_shadow: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-m2-heap-scan-shadow-branch.md"), List.of(
                "Storage Phase M2 — Heap scan shadow branch",
                "delosdb.storage.phaseM.heapScanShadow=true",
                "GenericResultSetFactory.getTableScanResultSet(...)",
                "DelosHeapScanShadowResultSet.createIfEnabled(params)",
                "default heap only, property-gated, read-only base scans only",
                "No DelosNativeTableRegistry heap registration",
                "M3 may attempt a supported-shape heap SELECT live route"));
    }

    private static void proveSourceShape() throws Exception {
        Path factory = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java");
        String factorySource = readSource(factory);
        require(factorySource.contains("DelosTableScanResultSet.createIfEnabled(params)"),
                "Expected delos_mvcc table-scan branch to remain first");
        require(factorySource.contains("DelosHeapScanShadowResultSet.createIfEnabled(params)"),
                "Expected M2 heap shadow branch at GenericResultSetFactory table-scan seam");
        require(factorySource.indexOf("DelosTableScanResultSet.createIfEnabled(params)")
                        < factorySource.indexOf("DelosHeapScanShadowResultSet.createIfEnabled(params)"),
                "Expected delos_mvcc branch to be tested before heap shadow branch");
        require(factorySource.indexOf("DelosHeapScanShadowResultSet.createIfEnabled(params)")
                        < factorySource.indexOf("return new TableScanResultSet(params)"),
                "Expected heap shadow branch to fall back to ordinary TableScanResultSet");

        Path shadow = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapScanShadowResultSet.java");
        assertSourceContains(shadow, List.of(
                "M2 property-gated heap scan shadow branch",
                "extends TableScanResultSet",
                "HEAP_SCAN_SHADOW_PROPERTY",
                "delosdb.storage.phaseM.heapScanShadow",
                "!Boolean.getBoolean(HEAP_SCAN_SHADOW_PROPERTY)",
                "params.forUpdate || hasIndexName(params.indexName)",
                "lookup.isEmpty() || !lookup.get().isDefaultStorageProvider()",
                "return Optional.of(new DelosHeapScanShadowResultSet(params, lookup.get()))",
                "shadowBranchCountForTesting",
                "lastShadowLookupForTesting"));
        assertSourceDoesNotContain(shadow, List.of(
                "EngineHeapTableAccessProof",
                "EngineHeapTableAccessLiveCandidate",
                "DelosNativeTableRegistry",
                "DelosMutableTableAccess",
                "DelosMvccReservableTableAccess",
                "reserveMutation(",
                "RowChangerImpl",
                "ConglomerateController"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanProviderLookup.java"), List.of(
                "FACTORY_HEAP_SCAN_SHADOW_PROPERTY",
                "resetHeapScanShadowForTesting",
                "heapScanShadowBranchCountForTesting",
                "lastHeapScanShadowLookupForTesting"));
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"), List.of(
                "lookup.isEmpty() || lookup.get().isDefaultStorageProvider()",
                "return Optional.empty()"));
    }

    private static void proveShadowDisabledLeavesHeapOnDerbyRoute() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        DelosTableScanProviderLookup.resetHeapScanShadowForTesting();
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
                require(rows.next(), "Expected heap SELECT to return row with shadow disabled");
                require("off".equals(rows.getString(1)), "Expected Derby-owned heap row with shadow disabled");
                require(!rows.next(), "Expected one heap row with shadow disabled");
            }
        }

        Optional<DelosTableScanProviderLookup.Result> observed =
                DelosTableScanProviderLookup.lastFactoryLookupForTesting();
        require(observed.isPresent(), "Expected provider lookup observation for disabled-shadow heap SELECT");
        require(observed.get().isDefaultStorageProvider(),
                "Expected disabled-shadow heap table to resolve as default provider");
        require(DelosTableScanProviderLookup.heapScanShadowBranchCountForTesting() == 0,
                "Expected heap shadow branch not to run when the M2 property is disabled");
        require(DelosTableScanProviderLookup.lastHeapScanShadowLookupForTesting().isEmpty(),
                "Expected no recorded heap shadow lookup when the M2 property is disabled");
    }

    private static void proveShadowEnabledReachesHeapShadowBranch() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        DelosTableScanProviderLookup.resetHeapScanShadowForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_SCAN_SHADOW_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + HEAP_ON_TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected second ordinary heap table creation to stay Derby-owned");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + HEAP_ON_TABLE + " VALUES (?, ?)", 2, "on") == 1,
                    "Expected ordinary heap INSERT to stay Derby-owned while shadow flag is enabled");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             PreparedStatement select = connection.prepareStatement(
                     "SELECT value FROM APP." + HEAP_ON_TABLE + " WHERE id = ?")) {
            select.setInt(1, 2);
            try (ResultSet rows = select.executeQuery()) {
                require(rows.next(), "Expected heap SELECT to return row with shadow enabled");
                require("on".equals(rows.getString(1)), "Expected Derby-owned heap row with shadow enabled");
                require(!rows.next(), "Expected one heap row with shadow enabled");
            }
        }

        require(DelosTableScanProviderLookup.heapScanShadowBranchCountForTesting() > 0,
                "Expected property-gated heap shadow branch to be reached for default-provider SELECT");
        Optional<DelosTableScanProviderLookup.Result> shadowLookup =
                DelosTableScanProviderLookup.lastHeapScanShadowLookupForTesting();
        require(shadowLookup.isPresent(), "Expected recorded heap shadow provider lookup");
        require(shadowLookup.get().isDefaultStorageProvider(),
                "Expected heap shadow lookup to be default-provider only");
        require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_ON_TABLE),
                "Expected heap shadow table not to be registered as a Delos native table");
    }

    private static void proveDelosMvccDoesNotUseHeapShadowBranch() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        DelosTableScanProviderLookup.resetHeapScanShadowForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_HEAP_SCAN_SHADOW_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected explicit delos_mvcc CREATE TABLE to register native provider table");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?)", 3, "mvcc") == 1,
                    "Expected delos_mvcc INSERT to use native provider path");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             PreparedStatement select = connection.prepareStatement(
                     "SELECT value FROM APP." + MVCC_TABLE + " WHERE id = ?")) {
            select.setInt(1, 3);
            try (ResultSet rows = select.executeQuery()) {
                require(rows.next(), "Expected delos_mvcc SELECT to return native row");
                require("mvcc".equals(rows.getString(1)), "Expected delos_mvcc row from native route");
                require(!rows.next(), "Expected one delos_mvcc row");
            }
        }

        Optional<DelosTableScanProviderLookup.Result> observed =
                DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting();
        require(observed.isPresent(), "Expected non-default provider lookup for delos_mvcc SELECT");
        require(observed.get().isProvider("delos_mvcc"),
                "Expected delos_mvcc SELECT to resolve before heap shadow branch");
        require(DelosTableScanProviderLookup.heapScanShadowBranchCountForTesting() == 0,
                "Expected delos_mvcc SELECT not to reach heap shadow branch");
        require(DelosTableScanProviderLookup.lastHeapScanShadowLookupForTesting().isEmpty(),
                "Expected no heap shadow lookup for delos_mvcc SELECT");
        require(DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE),
                "Expected delos_mvcc table to remain registered in native registry");
        require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_OFF_TABLE),
                "Expected heap table created before shadow not to be registered in native registry");
        require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_ON_TABLE),
                "Expected heap shadow table not to be registered in native registry");
    }

    private static void proveNoHeapProviderActivationOrMutationClaim() throws Exception {
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
    }

    private static String readSource(Path sourceFile) throws Exception {
        require(Files.exists(sourceFile), "Missing expected source file: " + sourceFile);
        return Files.readString(sourceFile);
    }

    private static void assertSourceContains(Path sourceFile, List<String> requiredMarkers) throws Exception {
        String text = readSource(sourceFile);
        for (String marker : requiredMarkers) {
            require(text.contains(marker), sourceFile + " is missing required M2 marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path sourceFile, List<String> forbiddenMarkers) throws Exception {
        String text = readSource(sourceFile);
        for (String marker : forbiddenMarkers) {
            require(!text.contains(marker), sourceFile + " contains forbidden M2 marker: " + marker);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
