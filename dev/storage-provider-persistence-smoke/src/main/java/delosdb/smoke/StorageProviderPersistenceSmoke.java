package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.TableStorageMetadataResolver;
import io.github.ggeorg.delosdb.spi.storage.TableStorageMetadata;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies the StorageProvider v0 table metadata seam survives descriptor reload.
 */
public final class StorageProviderPersistenceSmoke {
    private StorageProviderPersistenceSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        Class.forName("org.apache.derby.jdbc.EmbeddedDriver");

        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + databasePath + ";create=true");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table storage_provider_default_persist(id int)");
            statement.executeUpdate("create table storage_provider_heap_persist(id int) using heap");

            assertStorageProvider(connection, "APP", "STORAGE_PROVIDER_DEFAULT_PERSIST", "heap");
            assertStorageProvider(connection, "APP", "STORAGE_PROVIDER_HEAP_PERSIST", "heap");
        }

        shutdown(databasePath);

        try (Connection connection = DriverManager.getConnection("jdbc:derby:" + databasePath);
             Statement statement = connection.createStatement()) {
            assertStorageProvider(connection, "APP", "STORAGE_PROVIDER_DEFAULT_PERSIST", "heap");
            assertStorageProvider(connection, "APP", "STORAGE_PROVIDER_HEAP_PERSIST", "heap");

            statement.executeUpdate("drop table storage_provider_heap_persist");
            statement.executeUpdate("drop table storage_provider_default_persist");
        } finally {
            shutdown(databasePath);
        }

        System.out.println("DelosDB StorageProvider metadata persistence smoke test passed.");
    }

    private static void assertStorageProvider(
            Connection connection,
            String schemaName,
            String tableName,
            String expectedProviderName) throws Exception {
        TableStorageMetadata metadata = TableStorageMetadataResolver.resolve(connection, schemaName, tableName);
        if (!expectedProviderName.equals(metadata.providerName())) {
            throw new IllegalStateException("Expected storage provider " + expectedProviderName
                    + " for " + schemaName + "." + tableName
                    + " but was " + metadata.providerName());
        }
        if (!schemaName.equals(metadata.schemaName())) {
            throw new IllegalStateException("Expected schema " + schemaName + " but was " + metadata.schemaName());
        }
        if (!tableName.equals(metadata.tableName())) {
            throw new IllegalStateException("Expected table " + tableName + " but was " + metadata.tableName());
        }
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
