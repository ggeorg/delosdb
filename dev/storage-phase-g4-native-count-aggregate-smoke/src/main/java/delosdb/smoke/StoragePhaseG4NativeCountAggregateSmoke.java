package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase G4 proof: SELECT COUNT(*) over a delos_mvcc table runs through Derby's
 * normal scalar aggregate, with DelosTableScanResultSet supplying the source
 * rows natively and without falling back to the transitional SQL bridge.
 */
public final class StoragePhaseG4NativeCountAggregateSmoke {
    private static final String DATABASE_PATH = "storage-phase-g4-native-count-aggregate-db";
    private static final String TABLE_NAME = "G4_NATIVE_COUNT";

    private StoragePhaseG4NativeCountAggregateSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createDerbyCatalogTableAndSeedMvccRows();
            proveNativeCountAggregate();
        } finally {
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
            System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_g4_native_count_aggregate: PASS");
    }

    private static void createDerbyCatalogTableAndSeedMvccRows() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE " + TABLE_NAME + " (id INT, kind VARCHAR(16), value VARCHAR(32)) USING delos_mvcc")) {
                statement.executeUpdate();
            }
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G4 catalog setup must use Derby prepare/constant-action path, not bridge: "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, kind VARCHAR(16), value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected provider-owned MVCC table setup through existing transitional bridge path");
            insert(statement, 1, "odd", "one");
            insert(statement, 2, "even", "two");
            insert(statement, 3, "odd", "three");
            insert(statement, 4, "even", "four");
        }
        VersionedStorageSqlBridge.resetRouteClassifierForTesting();
    }

    private static void insert(Statement statement, int id, String kind, String value) throws Exception {
        require(statement.executeUpdate("INSERT INTO APP." + TABLE_NAME
                        + " VALUES (" + id + ", '" + kind + "', '" + value + "')") == 1,
                "Expected provider-owned MVCC row setup for id=" + id);
    }

    private static void proveNativeCountAggregate() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            System.setProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY, "true");
            System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY, "true");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM APP." + TABLE_NAME)) {
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next(), "Expected native COUNT(*) to return one aggregate row");
                    require(rows.getLong(1) == 4L,
                            "Expected native COUNT(*) aggregate to count four Delos rows, got " + rows.getLong(1));
                    require(!rows.next(), "Expected COUNT(*) to return exactly one aggregate row");
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM APP." + TABLE_NAME + " WHERE kind = ?")) {
                statement.setString(1, "odd");
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next(), "Expected native filtered COUNT(*) to return one aggregate row");
                    require(rows.getLong(1) == 2L,
                            "Expected native filtered COUNT(*) aggregate to count two odd rows, got " + rows.getLong(1));
                    require(!rows.next(), "Expected filtered COUNT(*) to return exactly one aggregate row");
                }
            }

            require(DelosTableScanProviderLookup.factoryLookupCountForTesting() > 0,
                    "Expected GenericResultSetFactory probe to observe native G4 COUNT(*) source scan");
            require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                    "G4 prepared COUNT(*) proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                            + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
