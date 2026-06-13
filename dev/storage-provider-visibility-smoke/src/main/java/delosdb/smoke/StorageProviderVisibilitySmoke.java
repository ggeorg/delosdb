package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.TableStorageMetadataResolver;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies the StorageProvider v0 metadata seam is inspectable through a stable
 * internal diagnostic helper.
 */
public final class StorageProviderVisibilitySmoke {
    private StorageProviderVisibilitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");

        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + databasePath + ";create=true");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table storage_provider_visible_default(id int)");
            statement.executeUpdate("create table storage_provider_visible_heap(id int) using heap");

            assertDiagnostic(connection,
                    "APP",
                    "STORAGE_PROVIDER_VISIBLE_DEFAULT",
                    "APP.STORAGE_PROVIDER_VISIBLE_DEFAULT storageProvider=heap");
            assertDiagnostic(connection,
                    "APP",
                    "STORAGE_PROVIDER_VISIBLE_HEAP",
                    "APP.STORAGE_PROVIDER_VISIBLE_HEAP storageProvider=heap");
        }

        shutdown(databasePath);

        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + databasePath);
             Statement statement = connection.createStatement()) {
            assertDiagnostic(connection,
                    "APP",
                    "STORAGE_PROVIDER_VISIBLE_DEFAULT",
                    "APP.STORAGE_PROVIDER_VISIBLE_DEFAULT storageProvider=heap");
            assertDiagnostic(connection,
                    "APP",
                    "STORAGE_PROVIDER_VISIBLE_HEAP",
                    "APP.STORAGE_PROVIDER_VISIBLE_HEAP storageProvider=heap");

            statement.executeUpdate("drop table storage_provider_visible_heap");
            statement.executeUpdate("drop table storage_provider_visible_default");
        } finally {
            shutdown(databasePath);
        }

        System.out.println("DelosDB StorageProvider visibility smoke test passed.");
    }

    private static void assertDiagnostic(
            Connection connection,
            String schemaName,
            String tableName,
            String expected) throws Exception {
        String actual = TableStorageMetadataResolver.describe(connection, schemaName, tableName);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Expected visibility diagnostic '" + expected + "' but was '" + actual + "'");
        }
        System.out.println(actual);
    }

    private static void shutdown(String databasePath) throws SQLException {
        try {
            DriverManager.getConnection("jdbc:derby:" + databasePath + ";shutdown=true").close();
        } catch (SQLException expected) {
            if ("08006".equals(expected.getSQLState())) {
                return;
            }

            String message = expected.getMessage();
            if (message != null && message.contains("No suitable driver")) {
                return;
            }

            throw expected;
        }
    }
}
