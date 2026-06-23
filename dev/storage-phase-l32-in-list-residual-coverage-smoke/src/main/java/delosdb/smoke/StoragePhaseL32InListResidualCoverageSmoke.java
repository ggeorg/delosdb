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

/**
 * L3.2 proof: delos_mvcc SELECT covers SQL IN-list predicates through the
 * native table-scan route plus Derby residual expression evaluation, without
 * pretending that IN is a generic Delos predicate or activating heap routing.
 */
public final class StoragePhaseL32InListResidualCoverageSmoke {
    private static final String DATABASE_PATH = "storage-phase-l32-in-list-residual-coverage-db";
    private static final String MVCC_TABLE = "L32_IN_LIST_MVCC";
    private static final String HEAP_TABLE = "L32_IN_LIST_HEAP";

    private StoragePhaseL32InListResidualCoverageSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveSourceShape();
            proveMvccInListCoverageThroughResidualRestriction();
            proveHeapStillDefaultRoute();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_l32_in_list_residual_coverage: PASS");
    }

    private static void proveSourceShape() throws Exception {
        String predicateList = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/PredicateList.java"));
        require(predicateList.contains("store can never treat \"in\" as qualifier"),
                "Expected Derby source truth: IN is not a normal store qualifier");
        require(predicateList.contains("generateInListValues"),
                "Expected Derby IN-list values to remain a separate execution construct");

        String multiProbe = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/MultiProbeTableScanResultSet.java"));
        require(multiProbe.contains("probeValues"),
                "Expected Derby IN-list index probing to remain owned by MultiProbeTableScanResultSet");

        String genericFactory = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"));
        require(genericFactory.contains("return new MultiProbeTableScanResultSet"),
                "Expected Derby MultiProbe factory route to remain Derby-native");
        require(!genericFactory.contains("createMultiProbeIfEnabled"),
                "L3.2 must not introduce a native MultiProbe branch yet");

        String predicateOperator = Files.readString(Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosPredicateOperator.java"));
        require(!predicateOperator.contains("IN_LIST"),
                "L3.2 must not add a fake generic Delos IN_LIST contract");
    }

    private static void proveMvccInListCoverageThroughResidualRestriction() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY, "true");
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + MVCC_TABLE + " (id INT, label VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc");
            insertRow(connection, 1, "one");
            insertRow(connection, 2, "two");
            insertRow(connection, 3, "three");
            insertRow(connection, 4, null);
            insertRow(connection, 5, "five");

            require(List.of(1, 3, 5).equals(ids(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE id IN (5, 1, 3, 3)")),
                    "Expected delos_mvcc IN-list integer coverage through native scan plus residual restriction");
            require(List.of(2, 5).equals(ids(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE label IN ('five', 'two')")),
                    "Expected delos_mvcc IN-list string coverage through native scan plus residual restriction");
            require(List.of(1).equals(ids(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE id IN (1, CAST(NULL AS INT))")),
                    "Expected SQL IN-list NULL member semantics to remain Derby-owned");
            require(ids(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE id IN (CAST(NULL AS INT))").isEmpty(),
                    "Expected all-NULL IN list to filter out rows under SQL UNKNOWN semantics");
        }

        require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                "Expected ResultSetFactory provider lookup during L3.2 IN-list SELECT");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting()
                        .filter(result -> result.isProvider("delos_mvcc"))
                        .isPresent(),
                "Expected L3.2 IN-list coverage to stay on delos_mvcc native table-scan route");
    }

    private static void proveHeapStillDefaultRoute() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY, "true");
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + " (id INT, label VARCHAR(32))");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (10, 'ten')");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (11, 'eleven')");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (12, NULL)");
            require(List.of(10, 12).equals(ids(connection,
                    "SELECT id FROM APP." + HEAP_TABLE + " WHERE id IN (12, 10)")),
                    "Expected heap IN-list SELECT to continue through Derby-native route");
        }

        require(DelosTableScanProviderLookup.lastFactoryLookupForTesting()
                        .filter(DelosTableScanProviderLookup.Result::isDefaultStorageProvider)
                        .isPresent(),
                "Expected heap IN-list SELECT to remain default-provider route under native full-scan property");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isEmpty(),
                "L3.2 must not route heap IN-list SELECT through non-default Delos provider access");
    }

    private static void insertRow(Connection connection, int id, String label) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO APP." + MVCC_TABLE + " VALUES (?, ?)")) {
            insert.setInt(1, id);
            if (label == null) {
                insert.setNull(2, java.sql.Types.VARCHAR);
            } else {
                insert.setString(2, label);
            }
            require(insert.executeUpdate() == 1, "Expected one inserted delos_mvcc row");
        }
    }

    private static List<Integer> ids(Connection connection, String sql) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        Collections.sort(ids);
        return ids;
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
