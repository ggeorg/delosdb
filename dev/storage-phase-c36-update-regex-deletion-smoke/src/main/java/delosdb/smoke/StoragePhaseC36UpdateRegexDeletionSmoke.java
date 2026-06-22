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
 * Phase C36 proof: the direct UPDATE equality regex route is deleted after C35
 * proved the equivalent Derby JavaCC / QueryTreeNode classifier route.
 */
public final class StoragePhaseC36UpdateRegexDeletionSmoke {
    private StoragePhaseC36UpdateRegexDeletionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Object directOwner = new Object();
        VersionedStorageSqlResult directUpdate = VersionedStorageSqlBridge.tryExecute(
                "UPDATE C36_DIRECT_UPDATE SET value = 'after' WHERE id = 1",
                directOwner,
                true,
                Connection.TRANSACTION_READ_COMMITTED);
        require(directUpdate == null,
                "direct bridge UPDATE equality without Derby parser context must not be handled by a regex fallback");

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c36-update-regex-deletion-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C36_UPDATE_REGEX_DELETION (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO C36_UPDATE_REGEX_DELETION VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C36_UPDATE_REGEX_DELETION VALUES (2, 'bravo')");
            statement.executeUpdate("INSERT INTO C36_UPDATE_REGEX_DELETION VALUES (3, 'charlie')");

            int updated = statement.executeUpdate("UPDATE C36_UPDATE_REGEX_DELETION SET value = 'parser' WHERE id = 2");
            require(updated == 1, "JavaCC-routed UPDATE should still update one row after regex deletion");
            require(Objects.equals("javacc-query-tree", VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "UPDATE equality route must use Derby JavaCC / QueryTreeNode after regex deletion");

            List<String> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery("SELECT * FROM C36_UPDATE_REGEX_DELETION")) {
                while (resultSet.next()) {
                    rows.add(resultSet.getInt(1) + ":" + resultSet.getString(2));
                }
            }
            require(rows.equals(List.of("1:alpha", "2:parser", "3:charlie")),
                    "JavaCC-routed UPDATE should leave the expected rows visible: " + rows);
        } finally {
            SmokeUtils.shutdown("storage-phase-c36-update-regex-deletion-db");
        }

        System.out.println("storage_phase_c36_update_regex_deletion: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
