package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase G/F-I closeout proof: supported delos_mvcc execution is now Derby-native.
 * The retired SQL bridge class, JDBC interception hook, and soft-G6 compatibility
 * wiring must be absent from the active production/build surface.
 */
public final class StoragePhaseGConsolidationSmoke {
    private static final String DATABASE_PATH = "storage-phase-g-consolidation-db";
    private static final String TABLE_NAME = "G_CONSOLIDATION";
    private static final String BRIDGE_CLASS_PATH =
            "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/VersionedStorageSqlBridge.java";
    private static final String BRIDGE_RESULT_PATH =
            "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/VersionedStorageSqlResult.java";
    private static final String STALE_COMPATIBILITY_PROPERTY = "delosdb.storage.sqlBridge.compatibility";

    private StoragePhaseGConsolidationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveBridgeClassAndJdbcHookAreGone();
            proveNoCompatibilityModeWiringInGradle();
            proveOldPhaseGradleFilesAreGone();
            proveStaleDevScaffoldingIsGone();
            proveNoNativeExecutionDependencyOnBridgeClassName();
            proveNativeLifecycleAfterBridgeDeletion();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_g_consolidation: PASS");
    }

    private static void proveBridgeClassAndJdbcHookAreGone() throws Exception {
        List<String> violations = new ArrayList<>();
        Path bridgeClass = Path.of(BRIDGE_CLASS_PATH);
        if (Files.exists(bridgeClass)) {
            violations.add("retired SQL bridge source still exists: " + bridgeClass);
        }
        Path bridgeResult = Path.of(BRIDGE_RESULT_PATH);
        if (Files.exists(bridgeResult)) {
            violations.add("retired SQL bridge result wrapper still exists: " + bridgeResult);
        }

        assertSourceDoesNotContain(
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/jdbc/EmbedStatement.java"),
                List.of("VersionedStorageSqlBridge", "VersionedStorageSqlResult", "isInterceptionEnabled()", "tryExecute("),
                violations);
        assertSourceDoesNotContain(
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/jdbc/EmbedConnection.java"),
                List.of("VersionedStorageSqlBridge.commit", "VersionedStorageSqlBridge.rollback"),
                violations);
        require(violations.isEmpty(), String.join("; ", violations));
    }

    private static void proveNoCompatibilityModeWiringInGradle() throws Exception {
        List<Path> gradleFiles = List.of(
                Path.of("gradle/storage-native-execution-closeout.gradle"),
                Path.of("gradle/delosdb-permanent-storage-guards.gradle"),
                Path.of("build.gradle"));
        List<String> violations = new ArrayList<>();
        for (Path gradleFile : gradleFiles) {
            if (!Files.exists(gradleFile)) {
                violations.add("missing expected Gradle file: " + gradleFile);
                continue;
            }
            String text = Files.readString(gradleFile);
            if (text.contains(STALE_COMPATIBILITY_PROPERTY)
                    || text.contains("BRIDGE_INTERCEPTION_COMPATIBILITY_PROPERTY")
                    || text.contains("BridgeReduction")
                    || text.contains("bridge-reduction")
                    || text.contains("VersionedStorageSqlBridge")) {
                violations.add("stale bridge/compatibility wiring in " + gradleFile);
            }
        }
        require(violations.isEmpty(), String.join("; ", violations));
    }

    private static void proveOldPhaseGradleFilesAreGone() {
        List<Path> retiredGradleFiles = List.of(
                Path.of("gradle/storage-phase-f-native-execution.gradle"),
                Path.of("gradle/storage-phase-g-native-predicates.gradle"),
                Path.of("gradle/storage-phase-h-cost-integration.gradle"),
                Path.of("gradle/storage-phase-i-mutation-concurrency.gradle"));
        List<String> present = new ArrayList<>();
        for (Path retiredGradleFile : retiredGradleFiles) {
            if (Files.exists(retiredGradleFile)) {
                present.add(retiredGradleFile.toString());
            }
        }
        require(present.isEmpty(), "retired per-phase Gradle files remain: " + present);
    }

    private static void proveStaleDevScaffoldingIsGone() {
        List<Path> stalePaths = List.of(
                Path.of("dev/storage-phase-f1a-provider-syntax-smoke"),
                Path.of("dev/storage-phase-f2-provider-catalog-smoke"),
                Path.of("dev/storage-phase-f2-resultset-lookup-smoke"),
                Path.of("dev/storage-phase-f3-factory-branch-smoke"),
                Path.of("dev/storage-phase-f3-delos-table-scan-skeleton-smoke"),
                Path.of("dev/storage-phase-f4-native-select-equality-smoke"),
                Path.of("dev/storage-phase-f5-native-insert-smoke"),
                Path.of("dev/storage-phase-f6-native-delete-equality-smoke"),
                Path.of("dev/storage-phase-f7-native-update-equality-smoke"),
                Path.of("dev/storage-phase-f8-bridge-bypass-smoke"),
                Path.of("dev/storage-phase-g0-native-qualifier-conjunction-smoke"),
                Path.of("dev/storage-phase-g1-native-range-predicates-smoke"),
                Path.of("dev/storage-phase-g2-native-between-predicates-smoke"),
                Path.of("dev/storage-phase-g3-native-select-all-smoke"),
                Path.of("dev/storage-phase-g4-native-count-aggregate-smoke"),
                Path.of("dev/storage-phase-g5-native-create-index-smoke"),
                Path.of("dev/storage-phase-g6-bridge-retirement-smoke"),
                Path.of("dev/storage-phase-g6-bridge-reduction-smoke"),
                Path.of("dev/storage-phase-h1-costable-table-access-smoke"),
                Path.of("dev/storage-phase-h2-mvcc-cost-mapping-smoke"),
                Path.of("dev/storage-phase-h3-heap-cost-proof-smoke"),
                Path.of("dev/storage-phase-i1-mutation-preparation-smoke"),
                Path.of("dev/versioned-storage-execution-bridge-smoke"),
                Path.of("storage-phase-g6-bridge-reduction-db"),
                Path.of("scripts/cleanup-storage-phase-g6-soft-bridge-reduction.sh"));
        List<String> present = new ArrayList<>();
        for (Path stalePath : stalePaths) {
            if (Files.exists(stalePath)) {
                present.add(stalePath.toString());
            }
        }
        require(present.isEmpty(), "stale per-step dev/bridge scaffolding remains: " + present);
    }

    private static void proveNoNativeExecutionDependencyOnBridgeClassName() throws Exception {
        List<Path> nativeExecutionFiles = List.of(
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/CreateTableConstantAction.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosInsertResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosDeleteResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosUpdateResultSet.java"),
                Path.of("delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"));
        List<String> violations = new ArrayList<>();
        for (Path sourceFile : nativeExecutionFiles) {
            assertSourceDoesNotContain(sourceFile, List.of("VersionedStorageSqlBridge"), violations);
        }
        require(violations.isEmpty(), String.join("; ", violations));
    }

    private static void proveNativeLifecycleAfterBridgeDeletion() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc to run after bridge deletion");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 7, "seven") == 1,
                    "Expected native INSERT to use provider storage registered by Derby CREATE TABLE");
            requireNativeRow(connection, 7, "seven", "before registry clear");
        }

        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", TABLE_NAME),
                "Expected G consolidation to clear only the native registry cache");
        SmokeUtils.shutdown(DATABASE_PATH);

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            requireNativeRow(connection, 7, "seven", "after Derby shutdown/reopen and registry cache clear");
        }
        require(DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", TABLE_NAME),
                "Expected native registry to be reconstructed from catalog metadata on first access");

        require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                "Expected ResultSetFactory provider lookup during G consolidation SELECT");
    }

    private static void assertSourceDoesNotContain(
            Path sourceFile,
            List<String> forbiddenMarkers,
            List<String> violations) throws Exception {
        if (!Files.exists(sourceFile)) {
            violations.add("missing expected source: " + sourceFile);
            return;
        }
        String text = Files.readString(sourceFile);
        for (String marker : forbiddenMarkers) {
            if (text.contains(marker)) {
                violations.add(sourceFile + " still contains stale marker: " + marker);
            }
        }
    }

    private static void requireNativeRow(Connection connection, int expectedId, String expectedValue, String label) throws Exception {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
            select.setInt(1, expectedId);
            try (ResultSet rows = select.executeQuery()) {
                require(rows.next(), "Expected one native row during G consolidation " + label);
                require(rows.getInt(1) == expectedId, "Unexpected id during G consolidation " + label);
                require(expectedValue.equals(rows.getString(2)), "Unexpected value during G consolidation " + label);
                require(!rows.next(), "Expected exactly one native row during G consolidation " + label);
            }
        }
    }

    private static void clearProofProperties() {
        System.clearProperty(STALE_COMPATIBILITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
