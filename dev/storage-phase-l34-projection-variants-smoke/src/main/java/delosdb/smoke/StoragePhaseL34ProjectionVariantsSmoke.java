package delosdb.smoke;

import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * L3.4 proof: delos_mvcc SELECT projection variants are covered by the native
 * table-scan route while Derby still owns projection/restriction semantics.
 */
public final class StoragePhaseL34ProjectionVariantsSmoke {
    private static final String DATABASE_PATH = "storage-phase-l34-projection-variants-db";
    private static final String MVCC_TABLE = "L34_PROJECTION_MVCC";
    private static final String HEAP_TABLE = "L34_PROJECTION_HEAP";

    private StoragePhaseL34ProjectionVariantsSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveSourceShape();
            proveMvccProjectionVariants();
            proveHeapStillDefaultRoute();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_l34_projection_variants: PASS");
    }

    private static void proveSourceShape() throws Exception {
        String scanResultSet = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"));
        require(scanResultSet.contains("NATIVE_PROJECTION_VARIANTS_PROPERTY"),
                "Expected a separate L3.4 projection-variant proof property");
        require(scanResultSet.contains("accessedColumnsForNativeScan"),
                "Expected native delos_mvcc scan to read Derby accessedCols from the prepared statement");
        require(scanResultSet.contains("getCompactRow(candidate, accessedCols, false)"),
                "Expected native delos_mvcc scan to compact base rows exactly like Derby table scans");
        require(scanResultSet.contains("materializeBaseRow"),
                "Expected native delos_mvcc scan to materialize a base-row candidate before compaction");
        require(scanResultSet.contains("DelosProjection.all()"),
                "Expected L3.4 to keep provider projection pushdown out of scope");
        require(!scanResultSet.contains("DelosProjection.columns("),
                "L3.4 must not introduce provider-side projection pushdown");
        require(!scanResultSet.contains("EngineHeapTableAccessLive"),
                "L3.4 must not introduce live heap Delos routing");

        String providerLookup = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanProviderLookup.java"));
        require(providerLookup.contains("FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY"),
                "Expected provider lookup to expose the L3.4 proof property for smokes");
    }

    private static void proveMvccProjectionVariants() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY, "true");
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, label VARCHAR(32), score INT) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc");
            insertRow(connection, 1, "one", 10);
            insertRow(connection, 2, "two", 20);
            insertRow(connection, 3, "three", 30);
            insertRow(connection, 4, null, 40);

            require(singleInt(connection,
                    "SELECT score FROM APP." + MVCC_TABLE + " WHERE id = 2") == 20,
                    "Expected single-column projection to use Derby accessedCols compaction");
            require("three".equals(singleString(connection,
                    "SELECT label FROM APP." + MVCC_TABLE + " WHERE score = 30")),
                    "Expected non-leading string projection to materialize the selected base column");
            require("30|three".equals(singlePair(connection,
                    "SELECT score, label FROM APP." + MVCC_TABLE + " WHERE id = 3")),
                    "Expected reordered projection to remain Derby-owned above native scan");
            require(singleInt(connection,
                    "SELECT score + id FROM APP." + MVCC_TABLE + " WHERE label = 'two'") == 22,
                    "Expected projected expression to evaluate above compact native scan rows");
            require(singleInt(connection,
                    "SELECT score FROM APP." + MVCC_TABLE + " WHERE label IS NULL") == 40,
                    "Expected NULL predicate plus projection to stay on native delos_mvcc scan route");
            require(singleString(connection,
                    "SELECT label FROM APP." + MVCC_TABLE + " WHERE id = 4") == null,
                    "Expected NULL projected value to survive native scan materialization and compaction");
            require(List.of(10, 20, 30, 40).equals(ints(connection,
                    "SELECT score FROM APP." + MVCC_TABLE)),
                    "Expected full-scan single-column projection under L3.4 proof gate");
        }

        require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                "Expected ResultSetFactory provider lookup during L3.4 projection SELECT");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting()
                        .filter(result -> result.isProvider("delos_mvcc"))
                        .isPresent(),
                "Expected L3.4 projection coverage to stay on delos_mvcc native table-scan route");
    }

    private static void proveHeapStillDefaultRoute() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY, "true");
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + " (id INT, label VARCHAR(32), score INT)");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'one', 10)");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (2, 'two', 20)");
            require(singleInt(connection,
                    "SELECT score FROM APP." + HEAP_TABLE + " WHERE id = 2") == 20,
                    "Expected heap projection SELECT to continue through Derby-native route");
        }

        require(DelosTableScanProviderLookup.lastFactoryLookupForTesting()
                        .filter(DelosTableScanProviderLookup.Result::isDefaultStorageProvider)
                        .isPresent(),
                "Expected heap projection SELECT to remain default-provider route under L3.4 property");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isEmpty(),
                "L3.4 must not route heap projection SELECT through non-default Delos provider access");
    }

    private static void insertRow(Connection connection, int id, String label, int score) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?, ?)")) {
            insert.setInt(1, id);
            if (label == null) {
                insert.setNull(2, java.sql.Types.VARCHAR);
            } else {
                insert.setString(2, label);
            }
            insert.setInt(3, score);
            require(insert.executeUpdate() == 1, "Expected one inserted delos_mvcc row");
        }
    }

    private static int singleInt(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "Expected one integer row for: " + sql);
            int value = rows.getInt(1);
            require(!rows.next(), "Expected exactly one integer row for: " + sql);
            return value;
        }
    }

    private static String singleString(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "Expected one string row for: " + sql);
            String value = rows.getString(1);
            require(!rows.next(), "Expected exactly one string row for: " + sql);
            return value;
        }
    }

    private static String singlePair(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "Expected one projected pair for: " + sql);
            String value = rows.getInt(1) + "|" + rows.getString(2);
            require(!rows.next(), "Expected exactly one projected pair for: " + sql);
            return value;
        }
    }

    private static List<Integer> ints(Connection connection, String sql) throws Exception {
        List<Integer> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getInt(1));
            }
        }
        Collections.sort(values);
        return values;
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
