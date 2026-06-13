package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.TableStorageCatalogMetadata;
import io.github.ggeorg.delosdb.engine.extension.storage.TableStorageMetadataResolver;
import io.github.ggeorg.delosdb.spi.storage.TableStorageMetadata;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Verifies provider metadata is catalog-backed, not just defaulted in memory.
 */
public final class ProviderCatalogIntegritySmoke {
    private ProviderCatalogIntegritySmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table provider_catalog_default(id int)");
            statement.executeUpdate("create table provider_catalog_explicit(id int) using heap");

            assertStored(connection, "APP", "PROVIDER_CATALOG_DEFAULT");
            assertStored(connection, "APP", "PROVIDER_CATALOG_EXPLICIT");
        }

        SmokeUtils.shutdown(databasePath);

        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertStored(connection, "APP", "PROVIDER_CATALOG_DEFAULT");
            assertStored(connection, "APP", "PROVIDER_CATALOG_EXPLICIT");

            statement.executeUpdate("drop table provider_catalog_explicit");
            statement.executeUpdate("drop table provider_catalog_default");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB provider catalog integrity smoke test passed.");
    }

    private static void assertStored(Connection connection, String schemaName, String tableName) throws Exception {
        TableStorageCatalogMetadata catalogMetadata =
                TableStorageMetadataResolver.resolveCatalogMetadata(connection, schemaName, tableName);
        TableStorageMetadata metadata = catalogMetadata.metadata();
        SmokeUtils.assertEquals("heap", metadata.providerName(), tableName + " storage provider");
        SmokeUtils.assertEquals(schemaName, metadata.schemaName(), tableName + " schema");
        SmokeUtils.assertEquals(tableName, metadata.tableName(), tableName + " table");
        SmokeUtils.assertEquals(
                TableStorageCatalogMetadata.Source.STORED,
                catalogMetadata.source(),
                tableName + " catalog metadata source");
        SmokeUtils.assertEquals("heap", catalogMetadata.storedProviderName(), tableName + " stored provider");
        System.out.println(catalogMetadata.describe());
    }
}
