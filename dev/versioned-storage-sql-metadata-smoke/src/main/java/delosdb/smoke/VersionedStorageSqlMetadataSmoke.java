package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase 5 smoke for the experimental delos_mvcc SQL table-scan path.
 *
 * <p>This still stays narrow: CREATE TABLE, INSERT, SELECT table scan,
 * COUNT(*), and explicit JDBC commit/rollback mapped to the provider-local
 * MVCC transaction lifecycle. Derby-compatible heap tables continue to use the
 * inherited execution path.</p>
 */
public final class VersionedStorageSqlMetadataSmoke {
    private VersionedStorageSqlMetadataSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table versioned_storage_heap(id int) using heap");
            statement.executeUpdate("insert into versioned_storage_heap values (1)");
            SmokeUtils.assertEquals("1",
                    SmokeUtils.singleString(statement, "select cast(id as char(1)) from versioned_storage_heap"),
                    "built-in heap storage remains executable");

            statement.executeUpdate("create table versioned_storage_mvcc(id int, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("insert into versioned_storage_mvcc values (1, 'alpha')");
            statement.executeUpdate("insert into versioned_storage_mvcc values (2, 'beta')");

            assertTableRows(statement, new Object[][]{
                    {1, "alpha"},
                    {2, "beta"}
            });
            assertCount(statement, 2, "auto-committed delos_mvcc rows");

            connection.setAutoCommit(false);
            statement.executeUpdate("insert into versioned_storage_mvcc values (3, 'rollback-me')");
            assertCount(statement, 3, "own uncommitted delos_mvcc insert is visible to same JDBC transaction");
            assertCountFromNewConnection(databasePath, 2, "uncommitted delos_mvcc insert is hidden from another JDBC transaction");
            connection.rollback();
            connection.setAutoCommit(true);
            assertCount(statement, 2, "rolled-back delos_mvcc insert is not visible");

            connection.setAutoCommit(false);
            statement.executeUpdate("insert into versioned_storage_mvcc values (3, 'gamma')");
            assertCount(statement, 3, "own pending delos_mvcc commit row is visible");
            assertCountFromNewConnection(databasePath, 2, "pending delos_mvcc commit row is hidden before commit");
            connection.commit();
            connection.setAutoCommit(true);
            assertCount(statement, 3, "committed delos_mvcc insert is visible");
            assertCountFromNewConnection(databasePath, 3, "committed delos_mvcc insert is visible to another JDBC transaction");

            statement.executeUpdate("drop table versioned_storage_heap");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage SQL transaction lifecycle smoke test passed.");
    }

    private static void assertTableRows(Statement statement, Object[][] expectedRows) throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from versioned_storage_mvcc")) {
            for (Object[] expected : expectedRows) {
                assertNext(rs, (Integer) expected[0], (String) expected[1]);
            }
            if (rs.next()) {
                throw new AssertionError("Unexpected extra row from delos_mvcc table scan");
            }
        }
    }

    private static void assertCountFromNewConnection(String databasePath, int expected, String message) throws Exception {
        try (Connection other = SmokeUtils.connect(databasePath, false);
             Statement otherStatement = other.createStatement()) {
            assertCount(otherStatement, expected, message);
        }
    }

    private static void assertCount(Statement statement, int expected, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select count(*) from versioned_storage_mvcc")) {
            if (!rs.next()) {
                throw new AssertionError("COUNT(*) returned no row for delos_mvcc table");
            }
            SmokeUtils.assertEquals(String.valueOf(expected), String.valueOf(rs.getInt(1)), message);
        }
    }

    private static void assertNext(ResultSet rs, int expectedId, String expectedName) throws Exception {
        if (!rs.next()) {
            throw new AssertionError("Expected row id=" + expectedId);
        }
        SmokeUtils.assertEquals(String.valueOf(expectedId), String.valueOf(rs.getInt(1)), "delos_mvcc id");
        SmokeUtils.assertEquals(expectedName, rs.getString(2), "delos_mvcc name");
    }
}
