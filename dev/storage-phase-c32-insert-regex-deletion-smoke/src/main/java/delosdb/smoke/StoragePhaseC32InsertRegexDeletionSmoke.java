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
 * Phase C32 proof: the INSERT VALUES regex route is retired only after its
 * Derby JavaCC / QueryTreeNode replacement is green.
 */
public final class StoragePhaseC32InsertRegexDeletionSmoke {
    private StoragePhaseC32InsertRegexDeletionSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Object directOwner = new Object();
        requireUpdateCount(VersionedStorageSqlBridge.tryExecute(
                        "CREATE TABLE C32_DIRECT_INSERT_REGEX_DELETION (id INT, value VARCHAR(40)) USING delos_mvcc",
                        directOwner,
                        true),
                0L,
                "direct CREATE TABLE");
        VersionedStorageSqlResult directWithoutDerbyContext = VersionedStorageSqlBridge.tryExecute(
                "INSERT INTO C32_DIRECT_INSERT_REGEX_DELETION VALUES (1, 'alpha')",
                directOwner,
                true,
                Connection.TRANSACTION_READ_COMMITTED);
        require(directWithoutDerbyContext == null,
                "direct bridge INSERT without Derby parser context must not be handled by a regex fallback");

        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect("storage-phase-c32-insert-regex-deletion-db", true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE C32_INSERT_REGEX_DELETION (id INT, value VARCHAR(40)) USING delos_mvcc");

            int inserted = statement.executeUpdate("INSERT INTO C32_INSERT_REGEX_DELETION VALUES (1, 'alpha')");
            require(inserted == 1, "JavaCC-routed INSERT should still insert one row after regex deletion");
            require(Objects.equals("javacc-query-tree", VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "INSERT VALUES route must use Derby JavaCC / QueryTreeNode after regex deletion");

            inserted = statement.executeUpdate("INSERT INTO C32_INSERT_REGEX_DELETION VALUES (2, 'bravo')");
            require(inserted == 1, "second JavaCC-routed INSERT should still insert one row after regex deletion");
            require(Objects.equals("javacc-query-tree", VersionedStorageSqlBridge.lastRouteClassifierForTesting().orElseThrow()),
                    "second INSERT VALUES route must also use Derby JavaCC / QueryTreeNode after regex deletion");

            List<String> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery("SELECT * FROM C32_INSERT_REGEX_DELETION")) {
                while (resultSet.next()) {
                    rows.add(resultSet.getInt(1) + ":" + resultSet.getString(2));
                }
            }
            require(rows.equals(List.of("1:alpha", "2:bravo")),
                    "JavaCC-routed INSERT rows should remain visible after regex deletion: " + rows);
        } finally {
            SmokeUtils.shutdown("storage-phase-c32-insert-regex-deletion-db");
        }

        System.out.println("storage_phase_c32_insert_regex_deletion: PASS");
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
