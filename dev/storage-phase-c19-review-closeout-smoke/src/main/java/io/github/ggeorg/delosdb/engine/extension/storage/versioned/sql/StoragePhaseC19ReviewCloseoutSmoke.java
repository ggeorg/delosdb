package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase C19 review closeout smoke: exercise the typed planned route seam and
 * index read delegation without using SQL strings or regex routing.
 */
public final class StoragePhaseC19ReviewCloseoutSmoke {
    private static final String TABLE_NAME = "C19_REVIEW_CLOSEOUT";
    private static final String INDEX_NAME = "C19_REVIEW_CLOSEOUT_VALUE_IDX";

    private StoragePhaseC19ReviewCloseoutSmoke() {
    }

    public static void main(String[] args) throws Exception {
        RouteOwner owner = new RouteOwner();
        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.createTable(
                        TABLE_NAME,
                        "id INT, value VARCHAR(40)"), owner),
                0L,
                "planned create table");
        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.createIndex(
                        INDEX_NAME,
                        TABLE_NAME,
                        "value"), owner),
                0L,
                "planned create index");
        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.insertValues(
                        TABLE_NAME,
                        "1, 'alpha'"), owner),
                1L,
                "planned insert alpha");
        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.insertValues(
                        TABLE_NAME,
                        "2, 'bravo'"), owner),
                1L,
                "planned insert bravo");
        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.insertValues(
                        TABLE_NAME,
                        "3, 'charlie'"), owner),
                1L,
                "planned insert charlie");

        requireRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectWhereEquals(
                        TABLE_NAME,
                        "value",
                        "'bravo'"), owner),
                List.of("bravo"));
        requireIndexAccessPath(INDEX_NAME, "planned equality lookup");

        requireRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectWhereBetween(
                        TABLE_NAME,
                        "value",
                        "'alpha'",
                        "'charlie'"), owner),
                List.of("alpha", "bravo", "charlie"));
        requireAnyAccessPath("planned range predicate");

        requireRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectAll(
                        TABLE_NAME,
                        "value",
                        "DESC"), owner),
                List.of("charlie", "bravo", "alpha"));
        requireIndexAccessPath(INDEX_NAME, "planned ordered lookup");

        requireCount(execute(VersionedStorageSqlBridge.PlannedRoute.selectCount(TABLE_NAME), owner), 3);

        System.out.println("storage_phase_c19_review_closeout table=" + TABLE_NAME + " index=" + INDEX_NAME);
        System.out.println("DelosDB Phase C19 review closeout smoke test passed.");
    }

    private static VersionedStorageSqlResult execute(
            VersionedStorageSqlBridge.PlannedRoute plannedRoute,
            Object owner) throws SQLException {
        return VersionedStorageSqlBridge.executePlannedRoute(
                plannedRoute,
                owner,
                true,
                Connection.TRANSACTION_READ_COMMITTED);
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
            throw new IllegalStateException("planned select did not return rows");
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

    private static void requireCount(VersionedStorageSqlResult result, int expectedCount)
            throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("planned count did not return rows");
        }
        try (ResultSet rows = result.resultSet()) {
            if (!rows.next()) {
                throw new IllegalStateException("expected one count row");
            }
            int actualCount = rows.getInt(1);
            if (actualCount != expectedCount) {
                throw new IllegalStateException("count expected=" + expectedCount + " actual=" + actualCount);
            }
            if (rows.next()) {
                throw new IllegalStateException("expected exactly one count row");
            }
        }
    }

    private static void requireAnyAccessPath(String label) {
        VersionedStorageSqlBridge.lastAccessPath()
                .orElseThrow(() -> new IllegalStateException(label + " did not expose an access path"));
    }

    private static void requireIndexAccessPath(String expectedIndexName, String label) {
        VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                .orElseThrow(() -> new IllegalStateException(label + " did not expose an access path"));
        if (!VersionedStorageAccessPath.INDEX_SCAN.equals(accessPath.selectedAccessMethod())) {
            throw new IllegalStateException(label + " expected provider-owned index scan but got "
                    + accessPath.selectedAccessMethod());
        }
        if (!expectedIndexName.equalsIgnoreCase(accessPath.selectedIndex())) {
            throw new IllegalStateException(label + " expected index=" + expectedIndexName
                    + " actual=" + accessPath.selectedIndex());
        }
    }

    private static final class RouteOwner {
    }
}
