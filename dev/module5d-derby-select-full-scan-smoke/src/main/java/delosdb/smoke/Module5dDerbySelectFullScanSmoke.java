package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * MODULE5D proof smoke: normal Derby SELECT full scan reaches delos_mvcc.
 *
 * <p>The smoke still uses the existing native INSERT proof route to seed rows,
 * but deliberately leaves the historical SELECT full-scan proof property unset.
 * A plain Derby SELECT over a {@code USING delos_mvcc} table must route to the
 * native MVCC scan because the persisted table storage-provider identity says
 * it is an MVCC-backed table, not because a test flag enabled a bridge path.</p>
 */
public final class Module5dDerbySelectFullScanSmoke {
    private static final String DATABASE_PATH = "build/module5d-derby-select-full-scan-db";
    private static final String TABLE = "MODULE5D_SELECT_FULL_SCAN";

    private Module5dDerbySelectFullScanSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        enableNativeInsertOnly();

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + TABLE
                    + " (id INT, name VARCHAR(20)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + TABLE + " VALUES (2, 'two')");
            statement.executeUpdate("INSERT INTO APP." + TABLE + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + TABLE + " VALUES (3, 'three')");

            assertNativeSelectFullScanPropertyIsNotSet();
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement),
                    "normal Derby SELECT full scan should read committed delos_mvcc rows");
            SmokeUtils.assertEquals(List.of("one", "three", "two"), names(statement),
                    "normal Derby SELECT full scan should materialize delos_mvcc row values");
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static List<Integer> ids(Statement statement) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id, name FROM APP." + TABLE)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        ids.sort(Integer::compareTo);
        return List.copyOf(ids);
    }

    private static List<String> names(Statement statement) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id, name FROM APP." + TABLE)) {
            while (rows.next()) {
                names.add(rows.getString(2));
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private static void enableNativeInsertOnly() {
        System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
    }

    private static void assertNativeSelectFullScanPropertyIsNotSet() {
        if (Boolean.getBoolean(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY)) {
            throw new AssertionError("MODULE5D must not rely on the old native SELECT full-scan proof property");
        }
    }

    private static void clearNativeRouteProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
    }
}
