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
 * MODULE5G preflight smoke: provider-identity routing is the real CRUD path.
 *
 * <p>This intentionally clears the old Phase F/G native proof properties before
 * executing normal Derby SQL.  A {@code USING delos_mvcc} table must route
 * INSERT, SELECT full scan, UPDATE, and DELETE through the native provider path
 * because the catalog table identity says it is MVCC-backed. A default heap
 * table in the same database must still use Derby's normal heap path.</p>
 *
 * <p>This is a consolidation proof before WAL/pageLSN work. It does not claim a
 * final Derby store/access provider: the Delos ResultSet family remains a
 * transitional execution seam until a lower access-store integration replaces
 * it.</p>
 */
public final class Module5gProviderRouteCrudPreflightSmoke {
    private static final String DATABASE_PATH = "build/module5g-provider-route-crud-preflight-db";
    private static final String MVCC_TABLE = "MODULE5G_CRUD_MVCC";
    private static final String HEAP_TABLE = "MODULE5G_CRUD_HEAP";

    private Module5gProviderRouteCrudPreflightSmoke() {
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

            assertNativeRoutePropertiesAreNotSet();

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'one')");
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'two')");
            SmokeUtils.assertEquals(List.of(1, 2), ids(statement, MVCC_TABLE),
                    "provider-identity INSERT plus SELECT should expose committed delos_mvcc rows");
            SmokeUtils.assertEquals(List.of("one", "two"), names(statement, MVCC_TABLE),
                    "provider-identity SELECT should materialize inserted delos_mvcc values");

            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (3, 'rollback')");
            SmokeUtils.assertEquals(List.of(1, 2, 3), ids(statement, MVCC_TABLE),
                    "same Derby transaction should see its own pending delos_mvcc insert");
            connection.rollback();
            SmokeUtils.assertEquals(List.of(1, 2), ids(statement, MVCC_TABLE),
                    "Derby rollback should hide the pending provider-routed delos_mvcc insert");

            statement.executeUpdate("UPDATE APP." + MVCC_TABLE + " SET name = 'updated'");
            SmokeUtils.assertEquals(List.of("updated", "updated"), names(statement, MVCC_TABLE),
                    "same Derby transaction should see provider-routed delos_mvcc UPDATE values");
            connection.commit();
            SmokeUtils.assertEquals(List.of("updated", "updated"), names(statement, MVCC_TABLE),
                    "Derby commit should preserve provider-routed delos_mvcc UPDATE values");

            SmokeUtils.assertEquals(2,
                    statement.executeUpdate("DELETE FROM APP." + MVCC_TABLE),
                    "provider-routed delos_mvcc DELETE should delete committed rows");
            connection.commit();
            SmokeUtils.assertEquals(List.of(), ids(statement, MVCC_TABLE),
                    "Derby commit should make provider-routed delos_mvcc DELETE visible");

            connection.setAutoCommit(true);
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (7, 'heap')");
            SmokeUtils.assertEquals(List.of(7), ids(statement, HEAP_TABLE),
                    "default heap INSERT should keep using Derby's normal heap path");
            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("UPDATE APP." + HEAP_TABLE + " SET name = 'heap-updated'"),
                    "default heap UPDATE should keep using Derby's normal heap path");
            SmokeUtils.assertEquals(List.of("heap-updated"), names(statement, HEAP_TABLE),
                    "default heap SELECT should preserve updated heap row values");
            SmokeUtils.assertEquals(1,
                    statement.executeUpdate("DELETE FROM APP." + HEAP_TABLE),
                    "default heap DELETE should keep using Derby's normal heap path");
            SmokeUtils.assertEquals(List.of(), ids(statement, HEAP_TABLE),
                    "default heap DELETE should remove heap rows normally");
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

    private static void assertNativeRoutePropertiesAreNotSet() {
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
    }

    private static void assertPropertyNotSet(String propertyName) {
        if (Boolean.getBoolean(propertyName)) {
            throw new AssertionError("MODULE5G preflight must not rely on old native proof property: " + propertyName);
        }
    }

    private static void clearNativeRouteProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
    }
}
