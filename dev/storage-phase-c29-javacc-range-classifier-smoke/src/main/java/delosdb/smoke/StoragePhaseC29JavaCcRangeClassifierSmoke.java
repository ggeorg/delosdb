package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase C29 proof: Derby JavaCC / QueryTreeNode inspection classifies a simple
 * range SELECT before the temporary regex fallback is considered.
 */
public final class StoragePhaseC29JavaCcRangeClassifierSmoke {
    private StoragePhaseC29JavaCcRangeClassifierSmoke() {
    }

    public static void main(String[] args) throws Exception {
        VersionedStorageSqlResult directRegexFallback = VersionedStorageSqlBridge.tryExecute(
                "SELECT * FROM C29_JAVACC_RANGE WHERE id > 2",
                new Object(),
                true,
                Connection.TRANSACTION_READ_COMMITTED);
        require(directRegexFallback == null,
                "direct range bridge without metadata should not be handled before the JDBC fixture exists");

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c29-javacc-range-classifier-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C29_JAVACC_RANGE (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("CREATE INDEX C29_JAVACC_RANGE_ID_IDX ON C29_JAVACC_RANGE(id)");
            statement.executeUpdate("INSERT INTO C29_JAVACC_RANGE VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C29_JAVACC_RANGE VALUES (2, 'bravo')");
            statement.executeUpdate("INSERT INTO C29_JAVACC_RANGE VALUES (3, 'charlie')");

            List<String> values = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT * FROM C29_JAVACC_RANGE WHERE id > 2")) {
                while (rows.next()) {
                    values.add(rows.getInt(1) + ":" + rows.getString(2));
                }
            }
            require(values.equals(List.of("3:charlie")),
                    "JavaCC-routed range SELECT should return id>2 rows through the range path: " + values);

            require(Objects.equals("javacc-query-tree",
                            VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "SELECT WHERE range route must be classified by Derby JavaCC / QueryTreeNode inspection");
            VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                    .orElseThrow(() -> new IllegalStateException("JavaCC-routed range SELECT did not expose access path"));
            require("select-range".equals(accessPath.operation()),
                    "JavaCC-routed range SELECT should feed the existing range SELECT path");
            require(VersionedStorageAccessPath.INDEX_SCAN.equals(accessPath.selectedAccessMethod()),
                    "JavaCC-routed range SELECT should still use provider-owned index selection when indexed");
        } finally {
            SmokeUtils.shutdown("storage-phase-c29-javacc-range-classifier-db");
        }

        System.out.println("storage_phase_c29_javacc_range_classifier: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
