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
 * Phase C34 proof: the direct DELETE equality regex route is deleted after C33
 * proved the equivalent Derby JavaCC / QueryTreeNode classifier route.
 */
public final class StoragePhaseC34DeleteRegexDeletionSmoke {
    private StoragePhaseC34DeleteRegexDeletionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Object directOwner = new Object();
        VersionedStorageSqlResult directDelete = VersionedStorageSqlBridge.tryExecute(
                "DELETE FROM C34_DIRECT_DELETE WHERE id = 1",
                directOwner,
                true,
                Connection.TRANSACTION_READ_COMMITTED);
        require(directDelete == null,
                "direct bridge DELETE equality without Derby parser context must not be handled by a regex fallback");

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c34-delete-regex-deletion-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C34_DELETE_REGEX_DELETION (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO C34_DELETE_REGEX_DELETION VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C34_DELETE_REGEX_DELETION VALUES (2, 'bravo')");
            statement.executeUpdate("INSERT INTO C34_DELETE_REGEX_DELETION VALUES (3, 'charlie')");

            int deleted = statement.executeUpdate("DELETE FROM C34_DELETE_REGEX_DELETION WHERE id = 2");
            require(deleted == 1, "JavaCC-routed DELETE should still delete one row after regex deletion");
            require(Objects.equals("javacc-query-tree", VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "DELETE equality route must use Derby JavaCC / QueryTreeNode after regex deletion");

            List<String> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery("SELECT * FROM C34_DELETE_REGEX_DELETION")) {
                while (resultSet.next()) {
                    rows.add(resultSet.getInt(1) + ":" + resultSet.getString(2));
                }
            }
            require(rows.equals(List.of("1:alpha", "3:charlie")),
                    "JavaCC-routed DELETE should leave the expected rows visible: " + rows);
        } finally {
            SmokeUtils.shutdown("storage-phase-c34-delete-regex-deletion-db");
        }

        System.out.println("storage_phase_c34_delete_regex_deletion: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
