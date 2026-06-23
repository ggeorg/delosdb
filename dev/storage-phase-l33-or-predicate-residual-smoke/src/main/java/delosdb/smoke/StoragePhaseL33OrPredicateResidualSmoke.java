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
 * L3.3 proof: delos_mvcc SELECT covers supported SQL OR predicate shapes via
 * the native table-scan route plus local qualifier/residual evaluation, without
 * adding a fake generic Delos OR predicate contract or activating heap routing.
 */
public final class StoragePhaseL33OrPredicateResidualSmoke {
    private static final String DATABASE_PATH = "storage-phase-l33-or-predicate-residual-db";
    private static final String MVCC_TABLE = "L33_OR_MVCC";
    private static final String HEAP_TABLE = "L33_OR_HEAP";

    private StoragePhaseL33OrPredicateResidualSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            proveSourceShape();
            proveMvccOrPredicateCoverage();
            proveHeapStillDefaultRoute();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_l33_or_predicate_residual: PASS");
    }

    private static void proveSourceShape() throws Exception {
        String predicateList = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/PredicateList.java"));
        require(predicateList.contains("trailing OR qualifiers"),
                "Expected Derby source truth: OR predicates can appear as trailing qualifier groups");
        require(predicateList.contains("pred.isOrList()"),
                "Expected Derby OR-list predicates to remain visible in PredicateList qualifier generation");
        require(predicateList.contains("1st OR predicate -> qual[1][0.. number of OR terms]"),
                "Expected Derby Qualifier[][] OR-group shape to remain unchanged");

        String scanResultSet = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosTableScanResultSet.java"));
        require(scanResultSet.contains("NATIVE_OR_PREDICATES_PROPERTY"),
                "Expected a separate L3.3 OR-predicate proof property");
        require(scanResultSet.contains("matchesLocalOrQualifierGroups"),
                "Expected DelosTableScanResultSet to evaluate OR qualifier groups locally");
        require(scanResultSet.contains("predicateMatchesNative"),
                "Expected local OR qualifier evaluation to use native delos_mvcc row values");
        require(!scanResultSet.contains("EngineHeapTableAccessLive"),
                "L3.3 must not introduce live heap Delos routing");

        String predicateOperator = Files.readString(Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosPredicateOperator.java"));
        List<String> predicateOperatorConstants = enumConstants(predicateOperator);
        require(!predicateOperatorConstants.contains("OR"),
                "L3.3 must not add a fake generic Delos OR predicate operator");
        require(!predicateOperatorConstants.contains("DISJUNCTION"),
                "L3.3 must not add a fake generic Delos disjunction contract");
    }

    private static void proveMvccOrPredicateCoverage() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY, "true");
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
            insertRow(connection, 5, "five", 50);

            require(List.of(1, 3).equals(ids(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE id = 1 OR id = 3")),
                    "Expected delos_mvcc equality OR coverage on native table-scan route");
            require(List.of(2, 5).equals(ids(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE label = 'two' OR label = 'five'")),
                    "Expected delos_mvcc string equality OR coverage on native table-scan route");
            require(List.of(1, 5).equals(ids(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE id < 2 OR id > 4")),
                    "Expected delos_mvcc range OR coverage on native table-scan route");
            require(List.of(4, 5).equals(ids(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE label IS NULL OR label = 'five'")),
                    "Expected delos_mvcc NULL/equality OR coverage on native table-scan route");
            require(List.of(1).equals(ids(connection,
                    "SELECT id FROM APP." + MVCC_TABLE + " WHERE label IS NOT NULL AND (id = 1 OR id = 4)")),
                    "Expected leading AND predicates to compose with OR groups without heap activation");
        }

        require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                "Expected ResultSetFactory provider lookup during L3.3 OR SELECT");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting()
                        .filter(result -> result.isProvider("delos_mvcc"))
                        .isPresent(),
                "Expected L3.3 OR coverage to stay on delos_mvcc native table-scan route");
    }

    private static void proveHeapStillDefaultRoute() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY, "true");
        DelosTableScanProviderLookup.resetFactoryLookupForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + " (id INT, label VARCHAR(32))");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (10, 'ten')");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (11, 'eleven')");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (12, NULL)");
            require(List.of(10, 12).equals(ids(connection,
                    "SELECT id FROM APP." + HEAP_TABLE + " WHERE id = 10 OR label IS NULL")),
                    "Expected heap OR SELECT to continue through Derby-native route");
        }

        require(DelosTableScanProviderLookup.lastFactoryLookupForTesting()
                        .filter(DelosTableScanProviderLookup.Result::isDefaultStorageProvider)
                        .isPresent(),
                "Expected heap OR SELECT to remain default-provider route under L3.3 property");
        require(DelosTableScanProviderLookup.lastNonDefaultFactoryLookupForTesting().isEmpty(),
                "L3.3 must not route heap OR SELECT through non-default Delos provider access");
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

    private static List<String> enumConstants(String enumSource) {
        String sourceWithoutComments = enumSource
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        int openBrace = sourceWithoutComments.indexOf('{');
        int close = sourceWithoutComments.indexOf(';', openBrace);
        if (close < 0) {
            close = sourceWithoutComments.indexOf('}', openBrace);
        }
        require(openBrace >= 0 && close > openBrace,
                "Expected DelosPredicateOperator enum constants to be parseable");

        List<String> constants = new ArrayList<>();
        String body = sourceWithoutComments.substring(openBrace + 1, close);
        for (String rawConstant : body.split(",")) {
            String constant = rawConstant.trim();
            if (constant.isEmpty()) {
                continue;
            }
            int constructorStart = constant.indexOf('(');
            if (constructorStart >= 0) {
                constant = constant.substring(0, constructorStart).trim();
            }
            int whitespace = firstWhitespace(constant);
            if (whitespace >= 0) {
                constant = constant.substring(0, whitespace).trim();
            }
            if (!constant.isEmpty()) {
                constants.add(constant);
            }
        }
        return constants;
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
