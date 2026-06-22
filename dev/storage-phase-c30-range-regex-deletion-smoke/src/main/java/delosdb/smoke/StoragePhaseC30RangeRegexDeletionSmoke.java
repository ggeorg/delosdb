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
 * Phase C30 proof: one range SELECT regex branch is retired only after its
 * Derby JavaCC / QueryTreeNode replacement is green.
 */
public final class StoragePhaseC30RangeRegexDeletionSmoke {
    private StoragePhaseC30RangeRegexDeletionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Object directOwner = new Object();
        requireUpdateCount(VersionedStorageSqlBridge.tryExecute(
                        "CREATE TABLE C30_DIRECT_RANGE_REGEX_DELETION (id INT, value VARCHAR(40)) USING delos_mvcc",
                        directOwner,
                        true),
                0L,
                "direct CREATE TABLE");
        requireUpdateCount(VersionedStorageSqlBridge.tryExecute(
                        "CREATE INDEX C30_DIRECT_RANGE_REGEX_DELETION_ID_IDX ON C30_DIRECT_RANGE_REGEX_DELETION(id)",
                        directOwner,
                        true),
                0L,
                "direct CREATE INDEX");
        requireUpdateCount(VersionedStorageSqlBridge.tryExecute(
                        "INSERT INTO C30_DIRECT_RANGE_REGEX_DELETION VALUES (1, 'alpha')",
                        directOwner,
                        true),
                1L,
                "direct INSERT 1");
        requireUpdateCount(VersionedStorageSqlBridge.tryExecute(
                        "INSERT INTO C30_DIRECT_RANGE_REGEX_DELETION VALUES (3, 'charlie')",
                        directOwner,
                        true),
                1L,
                "direct INSERT 3");
        VersionedStorageSqlResult directWithoutDerbyContext = VersionedStorageSqlBridge.tryExecute(
                "SELECT * FROM C30_DIRECT_RANGE_REGEX_DELETION WHERE id > 2",
                directOwner,
                true,
                Connection.TRANSACTION_READ_COMMITTED);
        require(directWithoutDerbyContext == null,
                "direct bridge SELECT > without Derby parser context must not be handled by a regex fallback");

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c30-range-regex-deletion-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C30_RANGE_REGEX_DELETION (id INT, value VARCHAR(40)) USING delos_mvcc");
            statement.executeUpdate("CREATE INDEX C30_RANGE_REGEX_DELETION_ID_IDX ON C30_RANGE_REGEX_DELETION(id)");
            statement.executeUpdate("INSERT INTO C30_RANGE_REGEX_DELETION VALUES (1, 'alpha')");
            statement.executeUpdate("INSERT INTO C30_RANGE_REGEX_DELETION VALUES (2, 'bravo')");
            statement.executeUpdate("INSERT INTO C30_RANGE_REGEX_DELETION VALUES (3, 'charlie')");

            List<String> values = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT * FROM C30_RANGE_REGEX_DELETION WHERE id > 2")) {
                while (rows.next()) {
                    values.add(rows.getInt(1) + ":" + rows.getString(2));
                }
            }
            require(values.equals(List.of("3:charlie")),
                    "JavaCC-routed range SELECT should still return id>2 rows after regex deletion: " + values);

            require(Objects.equals("javacc-query-tree",
                            VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "SELECT > route must use Derby JavaCC / QueryTreeNode after regex deletion");
            VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                    .orElseThrow(() -> new IllegalStateException("JavaCC-routed range SELECT did not expose access path"));
            require("select-range".equals(accessPath.operation()),
                    "JavaCC-routed range SELECT should still feed the existing range path after regex deletion");
            require(VersionedStorageAccessPath.INDEX_SCAN.equals(accessPath.selectedAccessMethod()),
                    "JavaCC-routed range SELECT should still use provider-owned index selection when indexed");
        } finally {
            SmokeUtils.shutdown("storage-phase-c30-range-regex-deletion-db");
        }

        System.out.println("storage_phase_c30_range_regex_deletion: PASS");
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
