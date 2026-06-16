package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase 9 smoke for PostgreSQL-guided snapshot semantics on delos_mvcc.
 *
 * <p>READ COMMITTED uses a fresh MVCC snapshot for each statement. REPEATABLE
 * READ keeps the same provider snapshot until JDBC commit/rollback. This is a
 * behavior proof only; it still uses the narrow Phase 8 SQL bridge and
 * provider-owned indexes.</p>
 */
public final class VersionedStorageSnapshotIsolationSmoke {
    private VersionedStorageSnapshotIsolationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection setup = SmokeUtils.connect(databasePath, true);
             Statement statement = setup.createStatement()) {
            statement.executeUpdate("create table versioned_storage_snapshot(id int, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("insert into versioned_storage_snapshot values (1, 'alpha')");
            statement.executeUpdate("create index vss_id_idx on versioned_storage_snapshot(id)");
        }

        try (Connection reader = SmokeUtils.connect(databasePath, false);
             Statement readerStatement = reader.createStatement()) {
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            reader.setAutoCommit(false);
            assertSingleName(readerStatement, "alpha", "repeatable-read first statement sees alpha");

            updateName(databasePath, "beta");
            assertSingleName(readerStatement, "alpha",
                    "repeatable-read transaction must keep its first MVCC snapshot after another commit");
            reader.rollback();

            reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            reader.setAutoCommit(false);
            assertSingleName(readerStatement, "beta", "read-committed first statement sees latest committed value");

            updateName(databasePath, "gamma");
            assertSingleName(readerStatement, "gamma",
                    "read-committed transaction must get a fresh MVCC snapshot on the next statement");
            reader.rollback();
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage snapshot-isolation smoke test passed.");
    }

    private static void updateName(String databasePath, String name) throws Exception {
        try (Connection writer = SmokeUtils.connect(databasePath, false);
             Statement writerStatement = writer.createStatement()) {
            writerStatement.executeUpdate("update versioned_storage_snapshot set name = '" + name + "' where id = 1");
        }
    }

    private static void assertSingleName(Statement statement, String expected, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from versioned_storage_snapshot")) {
            if (!rs.next()) {
                throw new AssertionError(message + ": expected one row, got none");
            }
            SmokeUtils.assertEquals("1", String.valueOf(rs.getInt(1)), message + " id");
            SmokeUtils.assertEquals(expected, rs.getString(2), message);
            if (rs.next()) {
                throw new AssertionError(message + ": expected one row, got extra id=" + rs.getInt(1));
            }
        }
    }
}
