package delosdb.smoke;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Verifies the SQL boundary for experimental versioned storage.
 *
 * <p>The parser and binder now recognize {@code CREATE TABLE ... USING delos_mvcc}
 * as an experimental VersionedStorageProvider name, but the statement must not
 * create a Derby heap table until the executor bridge exists.</p>
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
                    "heap table remains executable");

            assertVersionedStorageRejected(statement,
                    "create table versioned_storage_mvcc(id int) using delos_mvcc");
            assertTableNotCreated(statement, "select count(*) from versioned_storage_mvcc");

            statement.executeUpdate("drop table versioned_storage_heap");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage SQL metadata guard smoke test passed.");
    }

    private static void assertVersionedStorageRejected(Statement statement, String sql) throws SQLException {
        try {
            statement.executeUpdate(sql);
            throw new AssertionError("CREATE TABLE USING delos_mvcc unexpectedly succeeded");
        } catch (SQLException expected) {
            String sqlState = expected.getSQLState();
            if (sqlState == null || !sqlState.startsWith("0A000")) {
                throw expected;
            }

            String message = expected.getMessage();
            String lowerMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);
            if (!lowerMessage.contains("create table using delos_mvcc")
                    || !lowerMessage.contains("versionedstorageprovider")
                    || !lowerMessage.contains("sql execution is not implemented")) {
                throw new AssertionError("Unexpected delos_mvcc SQL diagnostic: " + message, expected);
            }
        }
    }

    private static void assertTableNotCreated(Statement statement, String sql) throws SQLException {
        try {
            statement.executeQuery(sql).close();
            throw new AssertionError("Rejected delos_mvcc table was unexpectedly created");
        } catch (SQLException expected) {
            String sqlState = expected.getSQLState();
            if (!"42X05".equals(sqlState)) {
                throw expected;
            }
        }
    }
}
