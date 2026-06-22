package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase C35 proof: UPDATE equality can be classified through Derby JavaCC /
 * QueryTreeNode. The direct UPDATE regex route remains as fallback until C36.
 */
public final class StoragePhaseC35JavaCcUpdateClassifierSmoke {
    private StoragePhaseC35JavaCcUpdateClassifierSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c35-javacc-update-classifier-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C35_JAVACC_UPDATE (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO C35_JAVACC_UPDATE VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C35_JAVACC_UPDATE VALUES (2, 'bravo')");
            statement.executeUpdate("INSERT INTO C35_JAVACC_UPDATE VALUES (3, 'charlie')");

            int updated = statement.executeUpdate("UPDATE C35_JAVACC_UPDATE SET value = 'parser' WHERE id = 2");
            require(updated == 1, "JavaCC-routed UPDATE should update one row");
            require(Objects.equals("javacc-query-tree", VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "UPDATE equality route should be classified by Derby JavaCC / QueryTreeNode");

            List<String> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery("SELECT * FROM C35_JAVACC_UPDATE")) {
                while (resultSet.next()) {
                    rows.add(resultSet.getInt(1) + ":" + resultSet.getString(2));
                }
            }
            require(rows.equals(List.of("1:alpha", "2:parser", "3:charlie")),
                    "JavaCC-routed UPDATE should leave expected rows visible: " + rows);
        } finally {
            SmokeUtils.shutdown("storage-phase-c35-javacc-update-classifier-db");
        }

        System.out.println("storage_phase_c35_javacc_update_classifier: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
