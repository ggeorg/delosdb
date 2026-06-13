package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Verifies the first SQL-facing StorageProvider v0 seam.
 *
 * <p>The smoke proves CREATE TABLE remains Derby-compatible by default and
 * accepts the explicit built-in heap provider. It deliberately does not add a
 * new physical storage engine or expose storage provider catalog persistence
 * yet.</p>
 */
public final class StorageProviderSyntaxSmoke {
    private StorageProviderSyntaxSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        String url = "jdbc:derby:" + databasePath + ";create=true";

        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table storage_provider_default(id int, name varchar(32))");
            statement.executeUpdate("create table storage_provider_heap(id int, name varchar(32)) using heap");

            statement.executeUpdate("insert into storage_provider_default values (1, 'default')");
            statement.executeUpdate("insert into storage_provider_heap values (2, 'heap')");

            assertRow(statement, "select name from storage_provider_default where id = 1", "default");
            assertRow(statement, "select name from storage_provider_heap where id = 2", "heap");

            assertUnsupportedProvider(statement,
                    "create table storage_provider_hash(id int) using hash",
                    "hash");
            assertUnsupportedProvider(statement,
                    "create table storage_provider_nope(id int) using nonsense",
                    "nonsense");

            statement.executeUpdate("drop table storage_provider_heap");
            statement.executeUpdate("drop table storage_provider_default");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB CREATE TABLE storage provider syntax smoke test passed.");
    }

    private static void assertRow(Statement statement, String sql, String expected) throws SQLException {
        try (ResultSet results = statement.executeQuery(sql)) {
            if (!results.next()) {
                throw new IllegalStateException("No row returned for " + sql);
            }

            String actual = results.getString(1);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Expected " + expected + " but was " + actual + " for " + sql);
            }

            if (results.next()) {
                throw new IllegalStateException("More than one row returned for " + sql);
            }
        }
    }

    private static void assertUnsupportedProvider(Statement statement, String sql, String providerName)
            throws SQLException {
        try {
            statement.executeUpdate(sql);
            throw new IllegalStateException("CREATE TABLE USING " + providerName + " unexpectedly succeeded");
        } catch (SQLException expected) {
            String sqlState = expected.getSQLState();
            if (sqlState == null || !sqlState.startsWith("0A000")) {
                throw expected;
            }

            String message = expected.getMessage();
            String lowerMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);
            if (!lowerMessage.contains("create table using " + providerName.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(
                        "Unsupported storage provider diagnostic did not identify CREATE TABLE USING "
                                + providerName + ": " + message,
                        expected);
            }
        }
    }

}
