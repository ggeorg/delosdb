package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase C31 proof: INSERT VALUES can be classified through Derby JavaCC /
 * QueryTreeNode. Later phases may remove the direct regex fallback, so this
 * proof is intentionally grounded in the Derby JDBC/parser path.
 */
public final class StoragePhaseC31JavaCcInsertClassifierSmoke {
    private StoragePhaseC31JavaCcInsertClassifierSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c31-javacc-insert-classifier-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C31_JAVACC_INSERT (id INT, value VARCHAR(40)) USING delos_mvcc");

            int inserted = statement.executeUpdate("INSERT INTO C31_JAVACC_INSERT VALUES (1, 'alpha')");
            require(inserted == 1, "JavaCC-routed INSERT should insert one row");
            require(Objects.equals("javacc-query-tree", VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "INSERT VALUES route should be classified by Derby JavaCC / QueryTreeNode");

            inserted = statement.executeUpdate("INSERT INTO C31_JAVACC_INSERT VALUES (2, 'bravo')");
            require(inserted == 1, "second JavaCC-routed INSERT should insert one row");
            require(Objects.equals("javacc-query-tree", VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "second INSERT VALUES route should also be classified by Derby JavaCC / QueryTreeNode");

            List<String> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery("SELECT * FROM C31_JAVACC_INSERT")) {
                while (resultSet.next()) {
                    rows.add(resultSet.getInt(1) + ":" + resultSet.getString(2));
                }
            }
            require(rows.equals(List.of("1:alpha", "2:bravo")),
                    "JavaCC-routed INSERT rows should be visible through existing SELECT * path: " + rows);
        } finally {
            SmokeUtils.shutdown("storage-phase-c31-javacc-insert-classifier-db");
        }

        System.out.println("storage_phase_c31_javacc_insert_classifier: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
