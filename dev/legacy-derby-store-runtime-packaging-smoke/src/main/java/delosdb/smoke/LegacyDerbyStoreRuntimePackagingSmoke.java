package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Proves that the compatibility runtime classpath can boot the inherited Derby
 * heap store from the assembled jars after the store source packages moved under
 * delosdb-storage-derby.
 *
 * <p>The Gradle task runs this smoke with {@code runtimeJars()} only. That
 * classpath intentionally does not include {@code delosdb-storage-derby.jar};
 * therefore this smoke fails if {@code derby.jar} no longer carries the legacy
 * store classes needed by existing jar-based users.</p>
 */
public final class LegacyDerbyStoreRuntimePackagingSmoke {
    private LegacyDerbyStoreRuntimePackagingSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        System.clearProperty("delosdb.storage.provider");
        System.clearProperty("delosdb.storage.defaultProvider");
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table ds10_heap_boot(id int primary key, name varchar(32))");
            statement.executeUpdate("create index ds10_heap_boot_name on ds10_heap_boot(name)");
            statement.executeUpdate("insert into ds10_heap_boot values (2, 'beta')");
            statement.executeUpdate("insert into ds10_heap_boot values (1, 'alpha')");
            assertOrderedRows(statement, "alpha", "beta", "initial heap/index read");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertOrderedRows(statement, "alpha", "beta", "reopened heap/index read");
            statement.executeUpdate("update ds10_heap_boot set name = 'gamma' where id = 2");
            SmokeUtils.assertEquals("gamma", SmokeUtils.singleString(
                    statement,
                    "select name from ds10_heap_boot where id = 2"),
                    "heap update after reopen");
            statement.executeUpdate("drop table ds10_heap_boot");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB legacy Derby store runtime packaging smoke test passed.");
    }

    private static void assertOrderedRows(
            Statement statement,
            String first,
            String second,
            String label) throws SQLException {
        try (ResultSet results = statement.executeQuery("select name from ds10_heap_boot order by name")) {
            if (!results.next()) {
                throw new AssertionError(label + ": missing first row");
            }
            SmokeUtils.assertEquals(first, results.getString(1), label + " first row");
            if (!results.next()) {
                throw new AssertionError(label + ": missing second row");
            }
            SmokeUtils.assertEquals(second, results.getString(1), label + " second row");
            if (results.next()) {
                throw new AssertionError(label + ": unexpected extra row");
            }
        }
    }
}
