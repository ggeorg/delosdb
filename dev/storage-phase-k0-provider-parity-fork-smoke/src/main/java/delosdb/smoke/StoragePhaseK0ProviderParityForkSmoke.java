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
 * K0 provider-parity fork proof.
 *
 * <p>This smoke deliberately does not choose the next architecture.  It freezes
 * the current truth before the fork: {@code delos_mvcc} is the only live Delos
 * native execution provider, while ordinary heap SQL remains on Derby's
 * inherited heap path and the heap Delos adapter remains proof-only.</p>
 */
public final class StoragePhaseK0ProviderParityForkSmoke {
    private static final String DATABASE_PATH = "storage-phase-k0-provider-parity-fork-db";
    private static final String HEAP_TABLE = "K0_HEAP";
    private static final String MVCC_TABLE = "K0_MVCC";

    private StoragePhaseK0ProviderParityForkSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveHeapAdapterIsStillProofOnlyInSource();
            proveHeapSqlStillUsesDefaultDerbyPath();
            proveMvccSqlStillUsesLiveNativeProviderPath();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_k0_provider_parity_fork: PASS");
    }

    private static void proveHeapAdapterIsStillProofOnlyInSource() throws Exception {
        Path heapProof = Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessProof.java");
        String heapProofSource = readSource(heapProof);
        require(heapProofSource.contains("proof adapter only"),
                "EngineHeapTableAccessProof must still document proof-only status before provider-parity decision");
        require(heapProofSource.contains("throw proofOnlyUnsupported"),
                "EngineHeapTableAccessProof runtime paths must still decline live heap execution before provider-parity decision");
        require(heapProofSource.contains("Derby heap scan still runs through TableScanResultSet"),
                "EngineHeapTableAccessProof must still state that live heap scan uses Derby's inherited path");
        require(heapProofSource.contains("Derby heap INSERT still runs through RowChangerImpl"),
                "EngineHeapTableAccessProof must still state that live heap INSERT uses Derby's inherited path");

        Path registry = Path.of("delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java");
        assertSourceContains(registry, List.of("PROVIDER_NAME = \"delos_mvcc\"", "NativeExecutionTableAccess"));
        assertSourceDoesNotContain(registry, List.of("EngineHeapTableAccessProof", "PROVIDER_NAME = \"heap\""));

        List<Path> nativeResultSets = List.of(
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosInsertResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosDeleteResultSet.java"),
                Path.of("delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosUpdateResultSet.java"));
        for (Path nativeResultSet : nativeResultSets) {
            assertSourceDoesNotContain(nativeResultSet, List.of("EngineHeapTableAccessProof"));
        }
    }

    private static void proveHeapSqlStillUsesDefaultDerbyPath() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");

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
                "Expected ordinary heap table to resolve as Derby default provider, not live Delos heap provider");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isEmpty(),
                "Expected ordinary heap SELECT not to be observed as a non-default native provider");
        require(!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_TABLE),
                "Expected ordinary heap table not to be registered in DelosNativeTableRegistry before provider parity is chosen");
    }

    private static void proveMvccSqlStillUsesLiveNativeProviderPath() throws Exception {
        clearProofProperties();
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected explicit delos_mvcc CREATE TABLE to use native provider registration");
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
                "Expected heap table to remain outside DelosNativeTableRegistry while provider parity is undecided");
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
    }

    private static String readSource(Path sourceFile) throws Exception {
        require(Files.exists(sourceFile), "Missing expected source file: " + sourceFile);
        return Files.readString(sourceFile);
    }

    private static void assertSourceContains(Path sourceFile, List<String> requiredMarkers) throws Exception {
        String text = readSource(sourceFile);
        for (String marker : requiredMarkers) {
            require(text.contains(marker), sourceFile + " is missing required marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path sourceFile, List<String> forbiddenMarkers) throws Exception {
        String text = readSource(sourceFile);
        for (String marker : forbiddenMarkers) {
            require(!text.contains(marker), sourceFile + " contains forbidden provider-parity marker before K decision: " + marker);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
