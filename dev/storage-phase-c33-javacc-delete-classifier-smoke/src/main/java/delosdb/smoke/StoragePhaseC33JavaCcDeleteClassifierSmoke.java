package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase C33 proof: DELETE equality can be classified through Derby JavaCC /
 * QueryTreeNode. The direct DELETE regex fallback remains until C34.
 */
public final class StoragePhaseC33JavaCcDeleteClassifierSmoke {
    private StoragePhaseC33JavaCcDeleteClassifierSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c33-javacc-delete-classifier-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C33_DIRECT_DELETE (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO C33_DIRECT_DELETE VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C33_DIRECT_DELETE VALUES (2, 'bravo')");

            Object directOwner = new Object();
            requireUpdateCount(VersionedStorageSqlBridge.tryExecute(
                            "DELETE FROM C33_DIRECT_DELETE WHERE id = 1",
                            directOwner,
                            true,
                            Connection.TRANSACTION_READ_COMMITTED),
                    1L,
                    "direct DELETE regex fallback");
            require(Objects.equals("regex", VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "direct DELETE equality should still use regex fallback in C33");

            statement.executeUpdate("CREATE TABLE C33_JAVACC_DELETE (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO C33_JAVACC_DELETE VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C33_JAVACC_DELETE VALUES (2, 'bravo')");
            statement.executeUpdate("INSERT INTO C33_JAVACC_DELETE VALUES (3, 'charlie')");

            int deleted = statement.executeUpdate("DELETE FROM C33_JAVACC_DELETE WHERE id = 2");
            require(deleted == 1, "JavaCC-routed DELETE should delete one row");
            require(Objects.equals("javacc-query-tree", VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "DELETE equality route should be classified by Derby JavaCC / QueryTreeNode");

            List<String> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery("SELECT * FROM C33_JAVACC_DELETE")) {
                while (resultSet.next()) {
                    rows.add(resultSet.getInt(1) + ":" + resultSet.getString(2));
                }
            }
            require(rows.equals(List.of("1:alpha", "3:charlie")),
                    "JavaCC-routed DELETE should leave the expected rows visible: " + rows);
        } finally {
            SmokeUtils.shutdown("storage-phase-c33-javacc-delete-classifier-db");
        }

        System.out.println("storage_phase_c33_javacc_delete_classifier: PASS");
    }

    private static void requireUpdateCount(VersionedStorageSqlResult result, long expected, String label) {
        if (result == null) {
            throw new IllegalStateException(label + " was not handled by VersionedStorageSqlBridge");
        }
        if (result.returnsRows()) {
            throw new IllegalStateException(label + " unexpectedly returned rows");
        }
        if (result.updateCount() != expected) {
            throw new IllegalStateException(label + " update count expected=" + expected
                    + " actual=" + result.updateCount());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
