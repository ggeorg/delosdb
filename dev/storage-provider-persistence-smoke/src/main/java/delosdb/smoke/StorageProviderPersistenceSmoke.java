package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.TableStorageMetadataResolver;
import io.github.ggeorg.delosdb.spi.storage.TableStorageMetadata;

import java.sql.Connection;
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
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table storage_provider_default_persist(id int)");
            statement.executeUpdate("create table storage_provider_heap_persist(id int) using heap");

            assertStorageProvider(connection, "APP", "STORAGE_PROVIDER_DEFAULT_PERSIST", "heap");
            assertStorageProvider(connection, "APP", "STORAGE_PROVIDER_HEAP_PERSIST", "heap");
        }

        SmokeUtils.shutdown(databasePath);

        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertStorageProvider(connection, "APP", "STORAGE_PROVIDER_DEFAULT_PERSIST", "heap");
            assertStorageProvider(connection, "APP", "STORAGE_PROVIDER_HEAP_PERSIST", "heap");

            statement.executeUpdate("drop table storage_provider_heap_persist");
            statement.executeUpdate("drop table storage_provider_default_persist");
        } finally {
            SmokeUtils.shutdown(databasePath);
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

}
