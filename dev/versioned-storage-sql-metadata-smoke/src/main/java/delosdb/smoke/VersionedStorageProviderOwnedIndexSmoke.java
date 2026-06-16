package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 8 smoke for provider-owned delos_mvcc indexes.
 *
 * <p>The proof is intentionally narrow and PostgreSQL-guided: the provider owns
 * the index, index lookup returns row candidates, and MVCC row/version
 * visibility remains authoritative. This is not Derby B-tree integration and
 * not optimizer costing yet.</p>
 */
public final class VersionedStorageProviderOwnedIndexSmoke {
    private VersionedStorageProviderOwnedIndexSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table versioned_storage_indexed(id int, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("insert into versioned_storage_indexed values (1, 'alpha')");
            statement.executeUpdate("insert into versioned_storage_indexed values (2, 'beta')");
            statement.executeUpdate("create index vsi_name_idx on versioned_storage_indexed(name)");

            assertRows(statement, "select * from versioned_storage_indexed where name = 'alpha'", new Object[][]{{1, "alpha"}});
            assertRows(statement, "select * from versioned_storage_indexed where name = 'beta'", new Object[][]{{2, "beta"}});
            assertRows(statement, "select * from versioned_storage_indexed where name = 'missing'", new Object[][]{});

            connection.setAutoCommit(false);
            statement.executeUpdate("insert into versioned_storage_indexed values (3, 'rollback-index')");
            assertRows(statement,
                    "select * from versioned_storage_indexed where name = 'rollback-index'",
                    new Object[][]{{3, "rollback-index"}});
            assertRowsFromNewConnection(databasePath,
                    "select * from versioned_storage_indexed where name = 'rollback-index'",
                    new Object[][]{},
                    "uncommitted indexed row must be hidden from another connection");
            connection.rollback();
            connection.setAutoCommit(true);
            assertRows(statement,
                    "select * from versioned_storage_indexed where name = 'rollback-index'",
                    new Object[][]{});

            statement.executeUpdate("update versioned_storage_indexed set name = 'gamma' where name = 'alpha'");
            assertRows(statement, "select * from versioned_storage_indexed where name = 'alpha'", new Object[][]{});
            assertRows(statement, "select * from versioned_storage_indexed where name = 'gamma'", new Object[][]{{1, "gamma"}});

            connection.setAutoCommit(false);
            statement.executeUpdate("update versioned_storage_indexed set name = 'old-reader-new' where name = 'gamma'");
            assertRowsFromNewConnection(databasePath,
                    "select * from versioned_storage_indexed where name = 'gamma'",
                    new Object[][]{{1, "gamma"}},
                    "committed reader must keep old indexed value before update commit");
            connection.commit();
            connection.setAutoCommit(true);
            assertRows(statement,
                    "select * from versioned_storage_indexed where name = 'old-reader-new'",
                    new Object[][]{{1, "old-reader-new"}});

            statement.executeUpdate("delete from versioned_storage_indexed where name = 'beta'");
            assertRows(statement, "select * from versioned_storage_indexed where name = 'beta'", new Object[][]{});
            assertCount(statement, 1, "deleted indexed row must be hidden from table scan too");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage provider-owned index smoke test passed.");
    }

    private static void assertRowsFromNewConnection(
            String databasePath,
            String sql,
            Object[][] expectedRows,
            String message) throws Exception {
        try (Connection other = SmokeUtils.connect(databasePath, false);
             Statement otherStatement = other.createStatement()) {
            try {
                assertRows(otherStatement, sql, expectedRows);
            } catch (AssertionError error) {
                throw new AssertionError(message + ": " + error.getMessage(), error);
            }
        }
    }

    private static void assertRows(Statement statement, String sql, Object[][] expectedRows) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            for (Object[] expected : expectedRows) {
                if (!rs.next()) {
                    throw new AssertionError("Expected indexed row id=" + expected[0] + " from " + sql);
                }
                SmokeUtils.assertEquals(String.valueOf(expected[0]), String.valueOf(rs.getInt(1)), "delos_mvcc indexed id");
                SmokeUtils.assertEquals((String) expected[1], rs.getString(2), "delos_mvcc indexed name");
            }
            if (rs.next()) {
                throw new AssertionError("Unexpected extra indexed row id=" + rs.getInt(1) + " from " + sql);
            }
        }
    }

    private static void assertCount(Statement statement, int expected, String message) throws SQLException {
        try (ResultSet rs = statement.executeQuery("select count(*) from versioned_storage_indexed")) {
            if (!rs.next()) {
                throw new AssertionError("COUNT(*) returned no row for delos_mvcc indexed table");
            }
            SmokeUtils.assertEquals(String.valueOf(expected), String.valueOf(rs.getInt(1)), message);
        }
    }
}
