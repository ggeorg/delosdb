package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

/**
 * Phase C25 proof: the first regex route is deleted only after its Derby
 * JavaCC / QueryTreeNode replacement is green.
 */
public final class StoragePhaseC25FirstRegexDeletionSmoke {
    private StoragePhaseC25FirstRegexDeletionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        VersionedStorageSqlResult directWithoutDerbyContext = VersionedStorageSqlBridge.tryExecute(
                "SELECT * FROM C25_FIRST_REGEX_DELETION WHERE id = 2",
                new Object(),
                true,
                Connection.TRANSACTION_READ_COMMITTED);
        require(directWithoutDerbyContext == null,
                "direct bridge SELECT equality without Derby parser context must not be handled by a regex fallback");

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c25-first-regex-deletion-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C25_FIRST_REGEX_DELETION (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("CREATE INDEX C25_FIRST_REGEX_DELETION_ID_IDX ON C25_FIRST_REGEX_DELETION(id)");
            statement.executeUpdate("INSERT INTO C25_FIRST_REGEX_DELETION VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C25_FIRST_REGEX_DELETION VALUES (2, 'bravo')");

            try (ResultSet rows = statement.executeQuery(
                    "SELECT * FROM C25_FIRST_REGEX_DELETION WHERE id = 2")) {
                require(rows.next(), "JavaCC-routed SELECT should return one row after regex deletion");
                require(rows.getInt(1) == 2, "JavaCC-routed SELECT should return id=2 after regex deletion");
                require(Objects.equals("bravo", rows.getString(2)),
                        "JavaCC-routed SELECT should return bravo after regex deletion");
                require(!rows.next(), "JavaCC-routed SELECT should return exactly one row after regex deletion");
            }

            require(Objects.equals("javacc-query-tree",
                            VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "SELECT WHERE equality route must use Derby JavaCC / QueryTreeNode after regex deletion");
            VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                    .orElseThrow(() -> new IllegalStateException("JavaCC-routed SELECT did not expose access path"));
            require("select-where".equals(accessPath.operation()),
                    "JavaCC-routed SELECT should still feed the table-access SELECT path after regex deletion");
            require(VersionedStorageAccessPath.INDEX_SCAN.equals(accessPath.selectedAccessMethod()),
                    "JavaCC-routed SELECT should still use the provider-owned index after regex deletion");
        } finally {
            SmokeUtils.shutdown("storage-phase-c25-first-regex-deletion-db");
        }

        System.out.println("storage_phase_c25_first_regex_deletion: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
