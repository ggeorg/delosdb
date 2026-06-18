package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies legacy Derby store behavior after removing store-source imports of
 * SQL execution classes. The proof intentionally exercises the two historical
 * leak areas from the SQL side: deferrable-constraint memory and store sort
 * row rendering/ordering paths.
 */
public final class LegacyDerbyStoreSqlExecutionSmoke {
    private LegacyDerbyStoreSqlExecutionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            assertDeferredConstraintMemoryPath(connection, statement);
            assertStoreSortPath(statement);
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB legacy Derby store SQL execution smoke test passed.");
    }

    private static void assertDeferredConstraintMemoryPath(Connection connection, Statement statement)
            throws SQLException {
        connection.setAutoCommit(false);
        statement.executeUpdate(
                "create table ds5_deferred_unique("
                        + "i int, "
                        + "j int, "
                        + "constraint ds5_u unique(i) deferrable initially deferred)");
        statement.executeUpdate("insert into ds5_deferred_unique values (1, 10)");
        statement.executeUpdate("insert into ds5_deferred_unique values (1, 20)");

        assertOrderedInts(statement,
                "select j from ds5_deferred_unique --DERBY-PROPERTIES constraint=ds5_u\norder by j",
                new int[] {10, 20},
                "deferred unique duplicate rows before commit");

        try {
            connection.commit();
            throw new AssertionError("Expected deferred unique constraint violation at commit");
        } catch (SQLException expected) {
            if (!"23506".equals(expected.getSQLState())) {
                throw expected;
            }
            connection.rollback();
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void assertStoreSortPath(Statement statement) throws SQLException {
        statement.executeUpdate("create table ds5_sort_probe(id int primary key, name varchar(32))");
        statement.executeUpdate("insert into ds5_sort_probe values (3, 'gamma')");
        statement.executeUpdate("insert into ds5_sort_probe values (1, 'alpha')");
        statement.executeUpdate("insert into ds5_sort_probe values (2, 'beta')");

        assertOrderedStrings(statement,
                "select name from ds5_sort_probe order by name",
                new String[] {"alpha", "beta", "gamma"},
                "store sort name ordering");
    }

    private static void assertOrderedInts(Statement statement, String sql, int[] expected, String label)
            throws SQLException {
        try (ResultSet results = statement.executeQuery(sql)) {
            for (int index = 0; index < expected.length; index++) {
                if (!results.next()) {
                    throw new AssertionError(label + " returned too few rows at index " + index);
                }
                int actual = results.getInt(1);
                SmokeUtils.assertEquals(expected[index], actual, label + " row " + index);
            }
            if (results.next()) {
                throw new AssertionError(label + " returned too many rows");
            }
        }
    }

    private static void assertOrderedStrings(Statement statement, String sql, String[] expected, String label)
            throws SQLException {
        try (ResultSet results = statement.executeQuery(sql)) {
            for (int index = 0; index < expected.length; index++) {
                if (!results.next()) {
                    throw new AssertionError(label + " returned too few rows at index " + index);
                }
                String actual = results.getString(1);
                SmokeUtils.assertEquals(expected[index], actual, label + " row " + index);
            }
            if (results.next()) {
                throw new AssertionError(label + " returned too many rows");
            }
        }
    }
}
