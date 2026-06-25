package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * MODULE5B proof smoke: Derby-visible table identity for delos_mvcc.
 *
 * <p>This intentionally exercises Derby's CREATE TABLE path and the cataloged
 * SYSTABLES.STORAGEPROVIDER value. It does not use the proof-only
 * DelosMvccSqlOptInSession regex bridge.</p>
 */
public final class Module5bNativeTableIdentitySmoke {
    private static final String DATABASE_PATH = "build/module5b-native-table-identity-db";
    private static final String MVCC_TABLE = "MODULE5B_MVCC_IDENTITY";
    private static final String HEAP_TABLE = "MODULE5B_HEAP_IDENTITY";

    private Module5bNativeTableIdentitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();

        SmokeUtils.loadEmbeddedDriver();
        try {
            createAndVerifyIdentities();
            verifyCatalogIdentitySurvivesRestart();
        } finally {
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createAndVerifyIdentities() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + " (id INT, name VARCHAR(20)) USING delos_mvcc");
            if (!DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", MVCC_TABLE)) {
                throw new AssertionError("Derby CREATE TABLE did not register delos_mvcc provider table identity");
            }
            SmokeUtils.assertEquals("delos_mvcc", storageProvider(statement, MVCC_TABLE),
                    "MVCC table catalog storage provider");

            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE
                    + " (id INT, name VARCHAR(20))");
            if (DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_TABLE)) {
                throw new AssertionError("Default heap table was incorrectly registered as a delos_mvcc provider table");
            }
            SmokeUtils.assertEquals(null, storageProvider(statement, HEAP_TABLE),
                    "heap table catalog storage provider");
        }
        SmokeUtils.shutdown(DATABASE_PATH);
    }

    private static void verifyCatalogIdentitySurvivesRestart() throws Exception {
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals("delos_mvcc", storageProvider(statement, MVCC_TABLE),
                    "reopened MVCC table catalog storage provider");
            SmokeUtils.assertEquals(null, storageProvider(statement, HEAP_TABLE),
                    "reopened heap table catalog storage provider");
            if (DelosNativeTableRegistry.hasRegisteredTableForTesting("APP", HEAP_TABLE)) {
                throw new AssertionError("Heap table appeared in native MVCC table registry after restart");
            }
        }
        SmokeUtils.shutdown(DATABASE_PATH);
    }

    private static String storageProvider(Statement statement, String tableName) throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT STORAGEPROVIDER FROM SYS.SYSTABLES WHERE TABLENAME = '" + tableName + "'")) {
            if (!rows.next()) {
                throw new AssertionError("No SYSTABLES row found for " + tableName);
            }
            String provider = rows.getString(1);
            if (rows.next()) {
                throw new AssertionError("More than one SYSTABLES row found for " + tableName);
            }
            return provider;
        }
    }
}
