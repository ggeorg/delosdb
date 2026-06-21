package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase C17 proof: a non-regex route can create a provider-owned MVCC index
 * while actual index creation delegates through VersionedStorageExecutionBridge.
 */
public final class StoragePhaseC17IndexDelegationSmoke {
    private static final String TABLE_NAME = "C17_INDEX_DELEGATION";
    private static final String INDEX_NAME = "C17_INDEX_DELEGATION_VALUE_IDX";

    private StoragePhaseC17IndexDelegationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        RouteOwner owner = new RouteOwner();
        requireUpdateCount(execute("CREATE_TABLE", groups(TABLE_NAME, "id INT, value VARCHAR(40)"), owner),
                0L,
                "planned create table");
        requireUpdateCount(execute("CREATE_INDEX", groups(INDEX_NAME, TABLE_NAME, "value"), owner),
                0L,
                "planned create index");
        requireUpdateCount(execute("INSERT_VALUES", groups(TABLE_NAME, "1, 'bravo'"), owner),
                1L,
                "planned insert bravo");
        requireUpdateCount(execute("INSERT_VALUES", groups(TABLE_NAME, "2, 'alpha'"), owner),
                1L,
                "planned insert alpha");
        requireRows(execute("SELECT_ALL", groups(TABLE_NAME, "value", "ASC"), owner), List.of("alpha", "bravo"));
        requireIndexAccessPath(INDEX_NAME);

        System.out.println("storage_phase_c17_index_delegation table=" + TABLE_NAME + " index=" + INDEX_NAME);
        System.out.println("DelosDB Phase C17 index delegation smoke test passed.");
    }

    private static VersionedStorageSqlResult execute(
            String routeType,
            List<String> groups,
            Object owner) throws SQLException {
        return VersionedStorageSqlBridge.executePlannedRouteForTesting(
                routeType,
                groups,
                owner,
                true,
                Connection.TRANSACTION_READ_COMMITTED);
    }

    private static List<String> groups(String... values) {
        List<String> groups = new ArrayList<>();
        for (String value : values) {
            groups.add(value);
        }
        return groups;
    }

    private static void requireUpdateCount(
            VersionedStorageSqlResult result,
            long expected,
            String label) {
        if (result == null) {
            throw new IllegalStateException(label + " was not handled by the planned route");
        }
        if (result.returnsRows()) {
            throw new IllegalStateException(label + " unexpectedly returned rows");
        }
        if (result.updateCount() != expected) {
            throw new IllegalStateException(label + " update count expected=" + expected
                    + " actual=" + result.updateCount());
        }
    }

    private static void requireRows(VersionedStorageSqlResult result, List<String> expectedValues)
            throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("planned ordered select did not return rows");
        }
        List<String> actualValues = new ArrayList<>();
        try (ResultSet rows = result.resultSet()) {
            while (rows.next()) {
                actualValues.add(rows.getString(2));
            }
        }
        if (!actualValues.equals(expectedValues)) {
            throw new IllegalStateException("rows expected=" + expectedValues + " actual=" + actualValues);
        }
    }

    private static void requireIndexAccessPath(String expectedIndexName) {
        VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                .orElseThrow(() -> new IllegalStateException("planned ordered select did not expose an access path"));
        if (!VersionedStorageAccessPath.INDEX_SCAN.equals(accessPath.selectedAccessMethod())) {
            throw new IllegalStateException("expected provider-owned index scan but got "
                    + accessPath.selectedAccessMethod());
        }
        if (!expectedIndexName.equalsIgnoreCase(accessPath.selectedIndex())) {
            throw new IllegalStateException("expected index=" + expectedIndexName
                    + " actual=" + accessPath.selectedIndex());
        }
    }

    private static final class RouteOwner {
    }
}
