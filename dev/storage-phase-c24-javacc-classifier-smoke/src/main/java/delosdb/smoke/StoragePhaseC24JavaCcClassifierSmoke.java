package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

/**
 * Phase C24 proof: classify one delos_mvcc SELECT through Derby JavaCC /
 * QueryTreeNode inspection before falling back to the temporary regex routes.
 */
public final class StoragePhaseC24JavaCcClassifierSmoke {
    private StoragePhaseC24JavaCcClassifierSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c24-javacc-classifier-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C24_JAVACC_ROUTE (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("CREATE INDEX C24_JAVACC_ROUTE_ID_IDX ON C24_JAVACC_ROUTE(id)");
            statement.executeUpdate("INSERT INTO C24_JAVACC_ROUTE VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C24_JAVACC_ROUTE VALUES (2, 'bravo')");

            try (ResultSet rows = statement.executeQuery("SELECT * FROM C24_JAVACC_ROUTE WHERE id = 2")) {
                require(rows.next(), "JavaCC-routed SELECT should return one row");
                require(rows.getInt(1) == 2, "JavaCC-routed SELECT should return id=2");
                require(Objects.equals("bravo", rows.getString(2)), "JavaCC-routed SELECT should return bravo");
                require(!rows.next(), "JavaCC-routed SELECT should return exactly one row");
            }

            require(Objects.equals("javacc-query-tree",
                            VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "SELECT WHERE equality route must be classified by Derby JavaCC / QueryTreeNode inspection");
            VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                    .orElseThrow(() -> new IllegalStateException("JavaCC-routed SELECT did not expose access path"));
            require("select-where".equals(accessPath.operation()),
                    "JavaCC-routed SELECT should feed the existing table-access SELECT path");
            require(VersionedStorageAccessPath.INDEX_SCAN.equals(accessPath.selectedAccessMethod()),
                    "JavaCC-routed SELECT should still reach DelosFilterableTableAccess.scan and provider-owned index selection");
        } finally {
            SmokeUtils.shutdown("storage-phase-c24-javacc-classifier-db");
        }
        System.out.println("storage_phase_c24_javacc_classifier: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
