package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase G3 proof: SELECT * over a delos_mvcc table can run as a native full
 * table scan through the Derby-generated execution path, without needing a
 * WHERE qualifier and without falling back to the transitional SQL bridge.
 */
public final class StoragePhaseG3NativeSelectAllSmoke {
    private static final String DATABASE_PATH = "storage-phase-g3-native-select-all-db";
    private static final String TABLE_NAME = "G3_NATIVE_SELECT_ALL";

    private StoragePhaseG3NativeSelectAllSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createDerbyCatalogTableAndSeedMvccRows();
            proveNativeSelectAllFullScan();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_g3_native_select_all: PASS");
    }

    private static void createDerbyCatalogTableAndSeedMvccRows() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE " + TABLE_NAME + " (id INT, kind VARCHAR(16), value VARCHAR(32)) USING delos_mvcc")) {
                statement.executeUpdate();
            }
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G3 catalog setup must use Derby prepare/constant-action path, not bridge: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, kind VARCHAR(16), value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected provider-owned MVCC table setup through existing transitional bridge path");
            insert(statement, 1, "odd", "one");
            insert(statement, 2, "even", "two");
            insert(statement, 3, "odd", "three");
        }
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
    }

    private static void insert(Statement statement, int id, String kind, String value) throws Exception {
        require(statement.executeUpdate("INSERT INTO APP." + TABLE_NAME
                        + " VALUES (" + id + ", '" + kind + "', '" + value + "')") == 1,
                "Expected provider-owned MVCC row setup for id=" + id);
    }

    private static void proveNativeSelectAllFullScan() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY, "true");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME)) {
                try (ResultSet rows = statement.executeQuery()) {
                    List<Row> actual = rows(rows);
                    require(actual.equals(List.of(
                                    new Row(1, "odd", "one"),
                                    new Row(2, "even", "two"),
                                    new Row(3, "odd", "three"))),
                            "Expected native SELECT * full scan to return all seeded rows, got " + actual);
                }
            }

            require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                    "Expected GenericResultSetFactory probe to observe native G3 SELECT * table scan");
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G3 prepared SELECT * proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }
    }

    private static List<Row> rows(ResultSet rows) throws Exception {
        List<Row> actual = new ArrayList<>();
        while (rows.next()) {
            actual.add(new Row(rows.getInt(1), rows.getString(2), rows.getString(3)));
        }
        actual.sort(Comparator.comparingInt(Row::id));
        return actual;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Row(int id, String kind, String value) {
    }
}
