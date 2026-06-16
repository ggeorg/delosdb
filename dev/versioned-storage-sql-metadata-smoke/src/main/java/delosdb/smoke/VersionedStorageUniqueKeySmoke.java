package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 7 smoke for minimal primary-key / unique conflict behavior on the
 * experimental delos_mvcc SQL path.
 *
 * <p>This is still table-scan storage: no B-tree, no optimizer integration, and
 * no Derby heap rewrite. The proof pins the first user-visible uniqueness
 * contract before secondary-index work starts.</p>
 */
public final class VersionedStorageUniqueKeySmoke {
    private VersionedStorageUniqueKeySmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table versioned_storage_unique("
                    + "id int primary key, name varchar(40) unique) using delos_mvcc");
            statement.executeUpdate("insert into versioned_storage_unique values (1, 'alpha')");

            expectSqlState(statement,
                    "insert into versioned_storage_unique values (1, 'duplicate-id')",
                    "23505",
                    "committed primary-key duplicate must fail");
            expectSqlState(statement,
                    "insert into versioned_storage_unique values (2, 'alpha')",
                    "23505",
                    "committed unique-column duplicate must fail");
            expectSqlState(statement,
                    "insert into versioned_storage_unique values (NULL, 'null-id')",
                    "23502",
                    "primary-key NULL must fail");

            connection.setAutoCommit(false);
            statement.executeUpdate("insert into versioned_storage_unique values (2, 'beta')");
            expectSqlStateFromNewConnection(databasePath,
                    "insert into versioned_storage_unique values (2, 'blocked-by-active-tx')",
                    "40XL1",
                    "active primary-key reservation must block another transaction");
            connection.rollback();
            connection.setAutoCommit(true);

            statement.executeUpdate("insert into versioned_storage_unique values (2, 'beta')");
            assertCount(statement, 2, "rolled-back primary-key reservation must be reusable");

            connection.setAutoCommit(false);
            statement.executeUpdate("insert into versioned_storage_unique values (3, 'gamma')");
            connection.commit();
            connection.setAutoCommit(true);

            expectSqlStateFromNewConnection(databasePath,
                    "insert into versioned_storage_unique values (3, 'duplicate-after-commit')",
                    "23505",
                    "committed primary-key reservation must remain enforced");
            assertCount(statement, 3, "successful unique delos_mvcc inserts");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage unique-key smoke test passed.");
    }

    private static void expectSqlStateFromNewConnection(
            String databasePath,
            String sql,
            String expectedState,
            String message) throws Exception {
        try (Connection other = SmokeUtils.connect(databasePath, false);
             Statement otherStatement = other.createStatement()) {
            expectSqlState(otherStatement, sql, expectedState, message);
        }
    }

    private static void expectSqlState(
            Statement statement,
            String sql,
            String expectedState,
            String message) throws Exception {
        try {
            statement.executeUpdate(sql);
            throw new AssertionError(message + ": expected SQLState " + expectedState + " but statement succeeded");
        } catch (SQLException e) {
            SmokeUtils.assertEquals(expectedState, e.getSQLState(), message);
        }
    }

    private static void assertCount(Statement statement, int expected, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select count(*) from versioned_storage_unique")) {
            if (!rs.next()) {
                throw new AssertionError("COUNT(*) returned no row for delos_mvcc unique table");
            }
            SmokeUtils.assertEquals(String.valueOf(expected), String.valueOf(rs.getInt(1)), message);
        }
    }
}
