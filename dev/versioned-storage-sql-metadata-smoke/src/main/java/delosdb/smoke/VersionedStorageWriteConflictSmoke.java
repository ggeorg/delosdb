package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Phase 12 smoke for delos_mvcc write/write conflict behavior through JDBC.
 *
 * <p>Readers keep seeing a stable committed version while another connection
 * holds an uncommitted update. A second writer against the same visible row is
 * rejected with a transaction-conflict SQLState, and rollback releases the
 * conflict for a later writer.</p>
 */
public final class VersionedStorageWriteConflictSmoke {
    private VersionedStorageWriteConflictSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection setup = SmokeUtils.connect(databasePath, true);
             Statement setupStatement = setup.createStatement()) {
            setupStatement.executeUpdate("create table versioned_storage_write_conflict("
                    + "id int primary key, name varchar(40)) using delos_mvcc");
            setupStatement.executeUpdate("insert into versioned_storage_write_conflict values (1, 'alpha')");
            setupStatement.executeUpdate("create index vswc_id_idx on versioned_storage_write_conflict(id)");
        }

        try (Connection writer = SmokeUtils.connect(databasePath, false);
             Statement writerStatement = writer.createStatement()) {
            writer.setAutoCommit(false);
            writerStatement.executeUpdate("update versioned_storage_write_conflict set name = 'locked' where id = 1");

            assertName(databasePath, "alpha", "reader must not see active writer value");
            expectSqlStateFromNewConnection(databasePath,
                    "update versioned_storage_write_conflict set name = 'blocked' where id = 1",
                    "40XL1",
                    "second writer must conflict with active writer");

            writer.rollback();
        }

        try (Connection writer = SmokeUtils.connect(databasePath, false);
             Statement writerStatement = writer.createStatement()) {
            writerStatement.executeUpdate("update versioned_storage_write_conflict set name = 'beta' where id = 1");
        }
        assertName(databasePath, "beta", "rollback must release write conflict for later writer");

        try (Connection deleter = SmokeUtils.connect(databasePath, false);
             Statement deleteStatement = deleter.createStatement()) {
            deleter.setAutoCommit(false);
            deleteStatement.executeUpdate("delete from versioned_storage_write_conflict where id = 1");
            expectSqlStateFromNewConnection(databasePath,
                    "update versioned_storage_write_conflict set name = 'blocked-delete' where id = 1",
                    "40XL1",
                    "active delete must conflict with concurrent update");
            deleter.rollback();
        }
        assertName(databasePath, "beta", "rollback of active delete must keep committed row visible");

        SmokeUtils.shutdown(databasePath);
        System.out.println("DelosDB versioned-storage write-conflict smoke test passed.");
    }

    private static void expectSqlStateFromNewConnection(
            String databasePath,
            String sql,
            String expectedState,
            String message) throws Exception {
        try (Connection other = SmokeUtils.connect(databasePath, false);
             Statement otherStatement = other.createStatement()) {
            try {
                otherStatement.executeUpdate(sql);
                throw new AssertionError(message + ": expected SQLState " + expectedState + " but statement succeeded");
            } catch (SQLException e) {
                SmokeUtils.assertEquals(expectedState, e.getSQLState(), message);
            }
        }
    }

    private static void assertName(String databasePath, String expected, String message) throws Exception {
        try (Connection reader = SmokeUtils.connect(databasePath, false);
             Statement readerStatement = reader.createStatement();
             ResultSet rs = readerStatement.executeQuery("select * from versioned_storage_write_conflict where id = 1")) {
            if (!rs.next()) {
                throw new AssertionError(message + ": expected one row, got none");
            }
            SmokeUtils.assertEquals("1", String.valueOf(rs.getInt(1)), message + " id");
            SmokeUtils.assertEquals(expected, rs.getString(2), message);
            if (rs.next()) {
                throw new AssertionError(message + ": expected one row, got extra row");
            }
        }
    }
}
