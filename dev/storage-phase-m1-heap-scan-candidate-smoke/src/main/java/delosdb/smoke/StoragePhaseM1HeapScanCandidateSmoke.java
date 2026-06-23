package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import org.apache.derby.iapi.store.types.DelosAccessContext;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableIdentity;
import org.apache.derby.iapi.store.types.DelosTableShape;
import org.apache.derby.impl.services.storetypes.EngineHeapTableAccessLiveCandidate;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * M1 heap scan live-candidate proof.
 *
 * <p>This smoke proves the candidate exists as an isolated direct-use scan
 * object only. It also proves ordinary heap SQL still uses Derby's native
 * TableScanResultSet route and no heap provider routing has been activated.</p>
 */
public final class StoragePhaseM1HeapScanCandidateSmoke {
    private static final String DATABASE_PATH = "storage-phase-m1-heap-scan-candidate-db";
    private static final String HEAP_TABLE = "M1_HEAP";

    private StoragePhaseM1HeapScanCandidateSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveDecisionDocument();
            proveCandidateSourceShape();
            proveCandidateIsPhysicallyGated();
            proveK1GuardAllowsCandidateButStillForbidsRouting();
            proveHeapSqlStillUsesDerbyNativeRoute();
            proveNoHeapRoutingOrRegistryActivation();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_m1_heap_scan_candidate: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-m1-heap-scan-candidate.md"), List.of(
                "Heap scan live-candidate, no SQL routing",
                "EngineHeapTableAccessLiveCandidate",
                "TransactionController.openScan",
                "TransactionController.openCompiledScan",
                "ScanController.fetchNext",
                "No GenericResultSetFactory heap branch",
                "No DelosNativeTableRegistry heap registration",
                "M2 — heap scan shadow branch"));
    }

    private static void proveCandidateSourceShape() throws Exception {
        Path candidate = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessLiveCandidate.java");
        assertSourceContains(candidate, List.of(
                "M1 isolated heap scan candidate",
                "implements DelosFilterableTableAccess",
                "DelosTableCapability.FILTERABLE",
                "Set.of()",
                "ROW_TEMPLATE_KEY",
                "openHeapScanCandidate",
                "TransactionController tc = context.require",
                "tc.openScan(",
                "tc.openCompiledScan(",
                "ScanController.GE",
                "ScanController.GT",
                "scanController.fetchNext(fetchRow)",
                "scanController.fetchLocation(rowLocation)",
                "EngineHeapTableAccessProof.rowIdentity(rowLocation)",
                "M1 heap scan candidate does not claim provider-side projection pushdown"));
        assertSourceDoesNotContain(candidate, List.of(
                "implements DelosMutableTableAccess",
                "implements DelosMvccReservableTableAccess",
                "DelosTableCapability.MUTABLE",
                "DelosTableGuarantee.ROW_LOCKING",
                "reserveMutation(",
                "insert(",
                "update(",
                "delete("));
    }

    private static void proveCandidateIsPhysicallyGated() {
        EngineHeapTableAccessLiveCandidate candidate = new EngineHeapTableAccessLiveCandidate(
                DelosTableIdentity.of("APP", "M1_DIRECT"),
                DelosTableShape.of(List.of(new DelosTableShape.Column("ID", "INTEGER", false))));
        require(candidate.capabilities().supports(DelosTableCapability.FILTERABLE),
                "Expected M1 candidate to advertise only the scan/filter surface");
        require(candidate.guarantees().isEmpty(),
                "Expected M1 candidate not to claim heap locking or durability guarantees");
        try {
            candidate.scan(DelosAccessContext.empty(false), new ArrayList<>(), DelosProjection.all());
            throw new IllegalStateException("Expected M1 candidate to require physical heap access context");
        } catch (IllegalStateException expected) {
            require(expected.getMessage().contains("heap physical access is not allowed"),
                    "Expected physical-access gate failure, got: " + expected.getMessage());
        }
    }

    private static void proveK1GuardAllowsCandidateButStillForbidsRouting() throws Exception {
        Path k1Smoke = Path.of(
                "dev/storage-phase-k1-heap-live-provider-feasibility-smoke/src/main/java/delosdb/smoke/StoragePhaseK1HeapLiveProviderFeasibilitySmoke.java");
        assertSourceContains(k1Smoke, List.of(
                "Later M-phase work may add an isolated heap scan candidate class",
                "K1's closed truth is that heap SQL routing remains Derby-native",
                "proveNoLiveHeapDelosRoutingHasAppeared"));
        assertSourceDoesNotContain(k1Smoke, List.of(
                "Expected no live heap Delos table-access candidate in K1"));
    }

    private static void proveHeapSqlStillUsesDerbyNativeRoute() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY, "true");

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
                "Expected ordinary heap table not to be registered as a Delos native table");
    }

    private static void proveNoHeapRoutingOrRegistryActivation() throws Exception {
        for (Path sourceFile : List.of(
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/FromBaseTable.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosInsertResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosDeleteResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosUpdateResultSet.java"),
                Path.of("delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"))) {
            assertSourceDoesNotContain(sourceFile, List.of(
                    "EngineHeapTableAccessLiveCandidate",
                    "heapScanShadow",
                    "phaseM.heapScanShadow"));
        }
        assertSourceContains(Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"), List.of(
                "lookup.isEmpty() || lookup.get().isDefaultStorageProvider()",
                "return Optional.empty()"));
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "PROVIDER_NAME = \"delos_mvcc\"",
                "EngineMvccTableAccess"));
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
            require(text.contains(marker), sourceFile + " is missing required M1 marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path sourceFile, List<String> forbiddenMarkers) throws Exception {
        String text = readSource(sourceFile);
        for (String marker : forbiddenMarkers) {
            require(!text.contains(marker), sourceFile + " contains forbidden M1 marker: " + marker);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
