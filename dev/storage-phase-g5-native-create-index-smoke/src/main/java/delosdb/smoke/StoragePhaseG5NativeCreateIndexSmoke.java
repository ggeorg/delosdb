package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.catalog.IndexDescriptor;
import org.apache.derby.impl.sql.execute.DelosCreateIndexProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Locale;

/**
 * Phase G5 proof: CREATE INDEX on a delos_mvcc table reaches Derby's native
 * CREATE INDEX constant-action path, resolves the table storage provider from
 * TableDescriptor metadata, and does not use the transitional SQL bridge route.
 */
public final class StoragePhaseG5NativeCreateIndexSmoke {
    private static final String DATABASE_PATH = "storage-phase-g5-native-create-index-db";
    private static final String TABLE_NAME = "G5_NATIVE_CREATE_INDEX";
    private static final String INDEX_NAME = "G5_NATIVE_CREATE_INDEX_ID_IDX";

    private StoragePhaseG5NativeCreateIndexSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createProviderCatalogTable();
            proveNativeCreateIndexProviderLookup();
        } finally {
            System.clearProperty(DelosCreateIndexProviderLookup.NATIVE_CREATE_INDEX_PROPERTY);
            DelosCreateIndexProviderLookup.resetForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_g5_native_create_index: PASS");
    }

    private static void createProviderCatalogTable() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE APP." + TABLE_NAME + " (ID INT, NAME VARCHAR(32)) USING delos_mvcc")) {
                require(statement.executeUpdate() == 0,
                        "Expected Derby catalog CREATE TABLE to report update count 0");
            }
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G5 catalog setup must use Derby prepare/constant-action path, not bridge: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }
    }

    private static void proveNativeCreateIndexProviderLookup() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosCreateIndexProviderLookup.resetForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            System.setProperty(DelosCreateIndexProviderLookup.NATIVE_CREATE_INDEX_PROPERTY, "true");

            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE INDEX APP." + INDEX_NAME + " ON APP." + TABLE_NAME + "(ID)")) {
                require(statement.executeUpdate() == 0,
                        "Expected native Derby CREATE INDEX to report update count 0");
            }

            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G5 CREATE INDEX proof path must not call the SQL bridge route: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
            require(DelosCreateIndexProviderLookup.lookupCountForTesting() == 1,
                    "Expected exactly one G5 CREATE INDEX provider lookup, got "
                            + DelosCreateIndexProviderLookup.lookupCountForTesting());

            DelosCreateIndexProviderLookup.Result lookup = DelosCreateIndexProviderLookup
                    .lastNonDefaultLookupForTesting()
                    .orElseThrow(() -> new IllegalStateException(
                            "Expected G5 CREATE INDEX lookup for non-default table storage provider"));
            require("APP".equals(lookup.schemaName()), "Expected schema APP, got " + lookup.schemaName());
            require(TABLE_NAME.equals(lookup.tableName()), "Expected table " + TABLE_NAME + ", got " + lookup.tableName());
            require(lookup.isStorageProvider("delos_mvcc"),
                    "Expected table storage provider delos_mvcc, got " + lookup.tableStorageProviderName());
            require(INDEX_NAME.equals(lookup.indexName()),
                    "Expected index " + INDEX_NAME + ", got " + lookup.indexName());
            require(List.of("ID").equals(lookup.columnNames()),
                    "Expected CREATE INDEX column list [ID], got " + lookup.columnNames());
            require(List.of(1).equals(lookup.baseColumnPositions()),
                    "Expected CREATE INDEX base column positions [1], got " + lookup.baseColumnPositions());
            require("btree".equals(lookup.indexProviderName()),
                    "Expected default Derby index provider btree, got " + lookup.indexProviderName());

            requireCatalogIndexDescriptor(connection);
        }
    }

    private static void requireCatalogIndexDescriptor(Connection connection) throws Exception {
        String sql = "SELECT descriptor FROM sys.sysconglomerates "
                + "WHERE conglomeratename = ? AND isindex = true";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, INDEX_NAME.toUpperCase(Locale.ROOT));
            try (ResultSet results = statement.executeQuery()) {
                require(results.next(), "Expected Derby catalog descriptor for G5 CREATE INDEX");
                Object descriptor = results.getObject(1);
                require(descriptor instanceof IndexDescriptor,
                        "Expected IndexDescriptor catalog object for G5 CREATE INDEX, got "
                                + (descriptor == null ? "null" : descriptor.getClass().getName()));
                IndexDescriptor indexDescriptor = (IndexDescriptor) descriptor;
                require("btree".equals(indexDescriptor.indexProviderName()),
                        "Expected catalog index provider btree, got " + indexDescriptor.indexProviderName());
                require(!results.next(), "Expected exactly one catalog index descriptor for " + INDEX_NAME);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
