package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.OptionalLong;

/**
 * A48 SQL bridge proof for statement-level MVCC command boundaries.
 *
 * <p>The kernel-level A46/A47 proofs establish command-aware own-write
 * visibility. This smoke verifies that the real Derby/JDBC bridge routes each
 * READ COMMITTED statement through a fresh provider-local statement command
 * while preserving one MVCC transaction until JDBC commit.</p>
 */
public final class VersionedStorageSqlStatementBoundarySmoke {
    private static final String TABLE_NAME = "sql_statement_boundary_mvcc";

    private VersionedStorageSqlStatementBoundarySmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();
        try {
            assertReadCommittedStatementsAdvanceMvccCommands(databasePath);
            assertCommittedStatementBoundaryStateVisibleFromNewConnection(databasePath);
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB MVCC SQL statement-boundary smoke test passed.");
    }

    private static void assertReadCommittedStatementsAdvanceMvccCommands(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            statement.executeUpdate("create table " + TABLE_NAME + "(id int primary key, name varchar(20)) using delos_mvcc");

            statement.executeUpdate("insert into " + TABLE_NAME + " values (1, 'alpha')");
            assertLastCommand(1L, "statement 1 insert command");

            assertSingleName(statement, "alpha", "statement 2 sees statement 1 insert");
            assertLastCommand(2L, "statement 2 read command");

            statement.executeUpdate("update " + TABLE_NAME + " set name = 'beta' where id = 1");
            assertLastCommand(3L, "statement 3 update command");

            assertSingleName(statement, "beta", "statement 4 sees statement 3 update");
            assertLastCommand(4L, "statement 4 read command");

            try (ResultSet beforeDelete = statement.executeQuery("select * from " + TABLE_NAME + " where id = 1")) {
                assertLastCommand(5L, "statement 5 read-before-delete command");
                if (!beforeDelete.next()) {
                    throw new AssertionError("statement 5 read-before-delete returned no row");
                }
                SmokeUtils.assertEquals("beta", beforeDelete.getString(2), "statement 5 read-before-delete value");
                if (beforeDelete.next()) {
                    throw new AssertionError("statement 5 read-before-delete returned extra row");
                }
            }

            statement.executeUpdate("delete from " + TABLE_NAME + " where id = 1");
            assertLastCommand(6L, "statement 6 delete command");

            assertNoRow(statement, "statement 7 sees statement 6 delete");
            assertLastCommand(7L, "statement 7 read-after-delete command");

            connection.commit();
        }
    }

    private static void assertCommittedStatementBoundaryStateVisibleFromNewConnection(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertNoRow(statement, "committed delete remains visible from a new JDBC connection");
        }
    }

    private static void assertSingleName(Statement statement, String expectedName, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from " + TABLE_NAME + " where id = 1")) {
            if (!rs.next()) {
                throw new AssertionError(message + ": no row returned");
            }
            SmokeUtils.assertEquals("1", String.valueOf(rs.getInt(1)), message + " id");
            SmokeUtils.assertEquals(expectedName, rs.getString(2), message + " name");
            if (rs.next()) {
                throw new AssertionError(message + ": unexpected extra row");
            }
        }
    }

    private static void assertNoRow(Statement statement, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from " + TABLE_NAME + " where id = 1")) {
            if (rs.next()) {
                throw new AssertionError(message + ": unexpected row id=" + rs.getInt(1));
            }
        }
    }

    private static void assertLastCommand(long expected, String message) {
        OptionalLong command = VersionedStorageSqlBridge.lastStatementCommandSequence();
        if (command.isEmpty()) {
            throw new AssertionError(message + ": no MVCC statement command was recorded");
        }
        SmokeUtils.assertEquals(String.valueOf(expected), String.valueOf(command.getAsLong()), message);
    }
}
