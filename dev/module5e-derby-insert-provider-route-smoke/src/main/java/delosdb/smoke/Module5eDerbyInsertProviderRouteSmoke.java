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
 * MODULE5E proof smoke: Derby INSERT routes to delos_mvcc by provider identity.
 *
 * <p>The historical F5 native INSERT proof property is deliberately left unset.
 * A normal Derby INSERT into a {@code USING delos_mvcc} table must reach the
 * native MVCC mutation path because the catalog table identity says the table is
 * provider-backed. A default heap table in the same database must still use the
 * normal Derby heap INSERT path.</p>
 */
public final class Module5eDerbyInsertProviderRouteSmoke {
    private static final String DATABASE_PATH = "build/module5e-derby-insert-provider-route-db";
    private static final String MVCC_TABLE = "MODULE5E_INSERT_MVCC";
    private static final String HEAP_TABLE = "MODULE5E_INSERT_HEAP";

    private Module5eDerbyInsertProviderRouteSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + " (id INT, name VARCHAR(20)) USING delos_mvcc");
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE
                    + " (id INT, name VARCHAR(20))");

            assertNativeInsertPropertyIsNotSet();
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'two')");
            SmokeUtils.assertEquals(List.of(1, 2), ids(statement, MVCC_TABLE),
                    "Derby INSERT should append committed delos_mvcc rows without the old proof property");
            SmokeUtils.assertEquals(List.of("one", "two"), names(statement, MVCC_TABLE),
                    "Derby INSERT should preserve delos_mvcc row values without the old proof property");

            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (7, 'heap')");
            SmokeUtils.assertEquals(List.of(7), ids(statement, HEAP_TABLE),
                    "default heap INSERT should keep using Derby's normal heap path");
            SmokeUtils.assertEquals(List.of("heap"), names(statement, HEAP_TABLE),
                    "default heap INSERT should preserve heap row values");
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static List<Integer> ids(Statement statement, String tableName) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id, name FROM APP." + tableName)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        ids.sort(Integer::compareTo);
        return List.copyOf(ids);
    }

    private static List<String> names(Statement statement, String tableName) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id, name FROM APP." + tableName)) {
            while (rows.next()) {
                names.add(rows.getString(2));
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private static void assertNativeInsertPropertyIsNotSet() {
        if (Boolean.getBoolean(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY)) {
            throw new AssertionError("MODULE5E must not rely on the old native INSERT proof property");
        }
    }

    private static void clearNativeRouteProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
    }
}
