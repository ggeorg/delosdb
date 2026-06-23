package delosdb.smoke;

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
 * L3.5 proof: delos_mvcc SELECT ORDER BY coverage uses the native table-scan
 * route while Derby still owns ORDER BY sorting above the scan.
 */
public final class StoragePhaseL35OrderByResidualSmoke {
    private static final String DATABASE_PATH = "storage-phase-l35-order-by-residual-db";
    private static final String MVCC_TABLE = "L35_ORDER_MVCC";
    private static final String HEAP_TABLE = "L35_ORDER_HEAP";

    private StoragePhaseL35OrderByResidualSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveSourceShape();
            proveMvccOrderByResidualSort();
            proveHeapStillDefaultRoute();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_l35_order_by_residual: PASS");
    }

    private static void proveSourceShape() throws Exception {
        String scanResultSet = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"));
        require(scanResultSet.contains("NATIVE_ORDER_BY_RESIDUAL_PROPERTY"),
                "Expected a separate L3.5 ORDER BY residual proof property");
        require(scanResultSet.contains("nativeOrderByResidualEnabled"),
                "Expected L3.5 to enable native full-scan coverage through an explicit proof gate");
        require(scanResultSet.contains("SortResultSet sort"),
                "Expected DelosTableScanResultSet.findIn(...) to recognize Derby SortResultSet wrappers");
        require(scanResultSet.contains("DelosProjection.all()"),
                "Expected L3.5 to keep provider projection/order pushdown out of scope");
        require(!scanResultSet.contains("DelosProjection.columns("),
                "L3.5 must not introduce provider-side projection pushdown");
        require(!scanResultSet.contains("DelosOrder"),
                "L3.5 must not introduce a provider-side ordered-scan contract");
        require(!scanResultSet.contains("EngineHeapTableAccessLive"),
                "L3.5 must not introduce live heap Delos routing");

        String providerLookup = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanProviderLookup.java"));
        require(providerLookup.contains("FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY"),
                "Expected provider lookup to expose the L3.5 ORDER BY proof property for smokes");

        String orderCompileSource = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/CursorNode.java"));
        require(orderCompileSource.contains("Push the order by list down to the ResultSet"),
                "Expected Derby ORDER BY compilation to remain visible in CursorNode");
        require(orderCompileSource.contains("order by columns") || orderCompileSource.contains("ORDER BY columns"),
                "Expected Derby to keep owning ORDER BY column pull-up/projection handling");
    }

    private static void proveMvccOrderByResidualSort() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY, "true");
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, label VARCHAR(32), score INT) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc");
            insertRow(connection, 1, "delta", 40);
            insertRow(connection, 2, "alpha", 10);
            insertRow(connection, 3, "charlie", 30);
            insertRow(connection, 4, "bravo", 20);

            require(List.of(2, 4, 3, 1).equals(intsInReturnedOrder(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " ORDER BY score")),
                    "Expected Derby residual ORDER BY ASC to sort rows from native delos_mvcc scan");
            require(List.of(1, 3, 4, 2).equals(intsInReturnedOrder(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " ORDER BY score DESC")),
                    "Expected Derby residual ORDER BY DESC to sort rows from native delos_mvcc scan");
            require(List.of("alpha", "bravo", "charlie", "delta").equals(stringsInReturnedOrder(connection,
                    "SELECT label FROM APP." + MVCC_TABLE + " ORDER BY score")),
                    "Expected ORDER BY on a non-selected column to remain Derby-owned above native scan");
            require(List.of(4, 3, 1).equals(intsInReturnedOrder(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE score >= 20 ORDER BY score")),
                    "Expected pushed range predicate plus residual ORDER BY to compose on native delos_mvcc scan");
            require(List.of(2, 4).equals(intsInReturnedOrder(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " ORDER BY score FETCH FIRST 2 ROWS ONLY")),
                    "Expected Derby RowCountResultSet above ORDER BY to work with native delos_mvcc scan");
        }

        require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                "Expected ResultSetFactory provider lookup during L3.5 ORDER BY SELECT");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting()
                        .filter(result -> result.isProvider("delos_mvcc"))
                        .isPresent(),
                "Expected L3.5 ORDER BY coverage to stay on delos_mvcc native table-scan route");
    }

    private static void proveHeapStillDefaultRoute() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY, "true");
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + " (id INT, label VARCHAR(32), score INT)");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'delta', 40)");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (2, 'alpha', 10)");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (3, 'charlie', 30)");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (4, 'bravo', 20)");
            require(List.of(2, 4, 3, 1).equals(intsInReturnedOrder(connection,
                    "SELECT id FROM APP." + HEAP_TABLE + " ORDER BY score")),
                    "Expected heap ORDER BY SELECT to continue through Derby-native route");
        }

        require(DelosTableScanProviderLookup.lastFactoryLookupForTesting()
                        .filter(DelosTableScanProviderLookup.Result::isDefaultStorageProvider)
                        .isPresent(),
                "Expected heap ORDER BY SELECT to remain default-provider route under L3.5 property");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isEmpty(),
                "L3.5 must not route heap ORDER BY SELECT through non-default Delos provider access");
    }

    private static void insertRow(Connection connection, int id, String label, int score) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?, ?)")) {
            insert.setInt(1, id);
            insert.setString(2, label);
            insert.setInt(3, score);
            require(insert.executeUpdate() == 1, "Expected one inserted delos_mvcc row");
        }
    }

    private static List<Integer> intsInReturnedOrder(Connection connection, String sql) throws Exception {
        List<Integer> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getInt(1));
            }
        }
        return values;
    }

    private static List<String> stringsInReturnedOrder(Connection connection, String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
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
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
