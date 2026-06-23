package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase G2 proof: Derby BETWEEN predicates reach the native Delos table scan.
 * Derby lowers BETWEEN to ordinary range qualifiers, so this proof keeps the
 * G1 range predicate machinery and proves the SQL surface no longer needs the
 * transitional bridge route.
 */
public final class StoragePhaseG2NativeBetweenPredicatesSmoke {
    private static final String DATABASE_PATH = "storage-phase-g2-native-between-predicates-db";
    private static final String TABLE_NAME = "G2_NATIVE_BETWEEN_PREDICATES";

    private StoragePhaseG2NativeBetweenPredicatesSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createDerbyCatalogTableAndSeedMvccRows();
            proveNativeBetweenPredicates();
            proveNativeBetweenAndEqualityConjunction();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_g2_native_between_predicates: PASS");
    }

    private static void createDerbyCatalogTableAndSeedMvccRows() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE " + TABLE_NAME + " (id INT, kind VARCHAR(16), value VARCHAR(32)) USING delos_mvcc")) {
                statement.executeUpdate();
            }
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G2 catalog setup must use Derby prepare/constant-action path, not bridge: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }

        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            insert(connection, 1, "odd", "one");
            insert(connection, 2, "even", "two");
            insert(connection, 3, "odd", "three");
            insert(connection, 4, "even", "four");
            insert(connection, 5, "odd", "five");
        }
        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "G2 native INSERT setup must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
    }

    private static void insert(Connection connection, int id, String kind, String value) throws Exception {
        require(SmokeUtils.executePreparedUpdate(connection,
                        "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?, ?)", id, kind, value) == 1,
                "Expected native MVCC row setup for id=" + id);
    }

    private static void proveNativeBetweenPredicates() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY, "true");

            requireIds(connection, 2, 4, List.of(2, 3, 4));
            requireIds(connection, 1, 1, List.of(1));
            requireIds(connection, 6, 9, List.of());

            require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                    "Expected GenericResultSetFactory probe to observe native G2 BETWEEN table scans");
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G2 prepared BETWEEN SELECT proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }
    }

    private static void proveNativeBetweenAndEqualityConjunction() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY, "true");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id BETWEEN ? AND ? AND kind = ?")) {
                statement.setInt(1, 2);
                statement.setInt(2, 5);
                statement.setString(3, "odd");
                try (ResultSet rows = statement.executeQuery()) {
                    List<Integer> actual = ids(rows);
                    require(actual.equals(List.of(3, 5)),
                            "Expected native BETWEEN+equality conjunction to return ids [3, 5], got " + actual);
                }
            }

            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G2 BETWEEN+equality conjunction proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }
    }

    private static void requireIds(Connection connection, int lower, int upper, List<Integer> expected)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM APP." + TABLE_NAME + " WHERE id BETWEEN ? AND ?")) {
            statement.setInt(1, lower);
            statement.setInt(2, upper);
            try (ResultSet rows = statement.executeQuery()) {
                List<Integer> actual = ids(rows);
                require(actual.equals(expected),
                        "Expected native BETWEEN predicate [" + lower + ", " + upper + "] to return ids "
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
