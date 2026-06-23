package delosdb.smoke;

import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase G1 proof: native Delos table scans translate Derby range qualifiers
 * into DelosPredicate range operators and evaluate them without falling back to
 * the transitional SQL bridge.
 */
public final class StoragePhaseG1NativeRangePredicatesSmoke {
    private static final String DATABASE_PATH = "storage-phase-g1-native-range-predicates-db";
    private static final String TABLE_NAME = "G1_NATIVE_RANGE_PREDICATES";

    private StoragePhaseG1NativeRangePredicatesSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createDerbyCatalogTableAndSeedMvccRows();
            proveNativeRangePredicates();
            proveNativeRangeAndEqualityConjunction();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_g1_native_range_predicates: PASS");
    }

    private static void createDerbyCatalogTableAndSeedMvccRows() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE " + TABLE_NAME + " (id INT, kind VARCHAR(16), value VARCHAR(32)) USING delos_mvcc")) {
                statement.executeUpdate();
            }
        }

        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            insert(connection, 1, "odd", "one");
            insert(connection, 2, "even", "two");
            insert(connection, 3, "odd", "three");
            insert(connection, 4, "even", "four");
        }
    }

    private static void insert(Connection connection, int id, String kind, String value) throws Exception {
        require(SmokeUtils.executePreparedUpdate(connection,
                        "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?, ?)", id, kind, value) == 1,
                "Expected native MVCC row setup for id=" + id);
    }

    private static void proveNativeRangePredicates() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY, "true");

            requireIds(connection, "id > ?", 2, List.of(3, 4));
            requireIds(connection, "id >= ?", 2, List.of(2, 3, 4));
            requireIds(connection, "id < ?", 3, List.of(1, 2));
            requireIds(connection, "id <= ?", 3, List.of(1, 2, 3));

            require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                    "Expected GenericResultSetFactory probe to observe native G1 table scans");
        }
    }

    private static void proveNativeRangeAndEqualityConjunction() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY, "true");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id >= ? AND kind = ?")) {
                statement.setInt(1, 2);
                statement.setString(2, "even");
                try (ResultSet rows = statement.executeQuery()) {
                    List<Integer> ids = ids(rows);
                    require(ids.equals(List.of(2, 4)),
                            "Expected native range+equality conjunction to return ids [2, 4], got " + ids);
                }
            }

        }
    }

    private static void requireIds(Connection connection, String predicateSql, int boundary, List<Integer> expected)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM APP." + TABLE_NAME + " WHERE " + predicateSql)) {
            statement.setInt(1, boundary);
            try (ResultSet rows = statement.executeQuery()) {
                List<Integer> actual = ids(rows);
                require(actual.equals(expected),
                        "Expected native range predicate " + predicateSql + " to return ids "
                                + expected + ", got " + actual);
            }
        }
    }

    private static List<Integer> ids(ResultSet rows) throws Exception {
        List<Integer> ids = new ArrayList<>();
        while (rows.next()) {
            ids.add(rows.getInt(1));
        }
        return ids;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
