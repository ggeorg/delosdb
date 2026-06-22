package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.TableStorageMetadataResolver;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.spi.storage.TableStorageMetadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Phase F2.1 proof: storageProviderName is no longer only in memory during
 * CREATE TABLE.  It is written to SYSTABLES and read back through Derby's
 * descriptor reconstruction after database restart.
 */
public final class StoragePhaseF2ProviderCatalogSmoke {
    private static final String DATABASE_PATH = "storage-phase-f2-provider-catalog-db";
    private static final String TABLE_NAME = "F2_PROVIDER_CATALOG";
    private static final String DEFAULT_TABLE_NAME = "F2_PROVIDER_DEFAULT";
    private static final String PROVIDER_NAME = "delos_mvcc";

    private StoragePhaseF2ProviderCatalogSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createTablesThroughDerbyPrepareExecute();
            SmokeUtils.shutdown(DATABASE_PATH);
            verifyProviderAfterRestart();
        } finally {
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f2_provider_catalog: PASS");
    }

    private static void createTablesThroughDerbyPrepareExecute() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "F2 should start with no bridge route classifier in this JVM");

            executePrepared(connection,
                    "CREATE TABLE " + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc");
            executePrepared(connection,
                    "CREATE TABLE " + DEFAULT_TABLE_NAME + " (id INT, value VARCHAR(32))");

            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "F2 CREATE TABLE proof must execute through Derby prepare/constant-action path, not bridge: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());

            assertCatalogProvider(connection, TABLE_NAME, PROVIDER_NAME);
            assertCatalogProvider(connection, DEFAULT_TABLE_NAME, null);
            assertDescriptorProvider(connection, TABLE_NAME, PROVIDER_NAME);
            assertDescriptorProvider(connection, DEFAULT_TABLE_NAME, "heap");
        }
    }

    private static void verifyProviderAfterRestart() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            assertCatalogProvider(connection, TABLE_NAME, PROVIDER_NAME);
            assertCatalogProvider(connection, DEFAULT_TABLE_NAME, null);
            assertDescriptorProvider(connection, TABLE_NAME, PROVIDER_NAME);
            assertDescriptorProvider(connection, DEFAULT_TABLE_NAME, "heap");
        }
    }

    private static void executePrepared(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static void assertCatalogProvider(Connection connection, String tableName, String expected)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT STORAGEPROVIDER FROM SYS.SYSTABLES WHERE TABLENAME = ?")) {
            statement.setString(1, tableName);
            try (ResultSet results = statement.executeQuery()) {
                require(results.next(), "Expected SYSTABLES row for " + tableName);
                String actual = results.getString(1);
                if (expected == null) {
                    require(actual == null, "Expected null STORAGEPROVIDER for " + tableName + " but was " + actual);
                } else {
                    require(expected.equals(actual),
                            "Expected STORAGEPROVIDER " + expected + " for " + tableName + " but was " + actual);
                }
                require(!results.next(), "Expected exactly one SYSTABLES row for " + tableName);
            }
        }
    }

    private static void assertDescriptorProvider(Connection connection, String tableName, String expected)
            throws Exception {
        TableStorageMetadata metadata = TableStorageMetadataResolver.resolve(connection, "APP", tableName);
        require(expected.equals(metadata.providerName()),
                "Expected descriptor provider " + expected + " for APP." + tableName
                        + " but was " + metadata.providerName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
