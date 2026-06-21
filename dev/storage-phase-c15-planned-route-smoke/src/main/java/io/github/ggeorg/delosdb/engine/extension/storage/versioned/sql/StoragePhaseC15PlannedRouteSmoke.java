package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase C15 proof: execute one planned/routed statement path without entering
 * SQL regex classification. The temporary SQL bridge still owns regex routing,
 * but routed statement execution is now callable from a non-regex route.
 */
public final class StoragePhaseC15PlannedRouteSmoke {
    private static final String TABLE_NAME = "C15_PLANNED_ROUTE";

    private StoragePhaseC15PlannedRouteSmoke() {
    }

    public static void main(String[] args) throws Exception {
        RouteOwner owner = new RouteOwner();
        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.createTable(TABLE_NAME, "id INT, value VARCHAR(40)"), owner),
                0L,
                "planned create table");
        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.insertValues(TABLE_NAME, "1, 'alpha'"), owner),
                1L,
                "planned insert alpha");
        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.insertValues(TABLE_NAME, "2, 'bravo'"), owner),
                1L,
                "planned insert bravo");

        requireRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectAll(TABLE_NAME, null, null), owner), List.of("alpha", "bravo"));
        requireCount(execute(VersionedStorageSqlBridge.PlannedRoute.selectCount(TABLE_NAME), owner), 2);

        System.out.println("storage_phase_c15_planned_route table=" + TABLE_NAME);
        System.out.println("DelosDB Phase C15 planned route smoke test passed.");
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
        List<String> sortedActual = new ArrayList<>(actualValues);
        List<String> sortedExpected = new ArrayList<>(expectedValues);
        java.util.Collections.sort(sortedActual);
        java.util.Collections.sort(sortedExpected);
        if (!sortedActual.equals(sortedExpected)) {
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

    private static final class RouteOwner {
    }
}
