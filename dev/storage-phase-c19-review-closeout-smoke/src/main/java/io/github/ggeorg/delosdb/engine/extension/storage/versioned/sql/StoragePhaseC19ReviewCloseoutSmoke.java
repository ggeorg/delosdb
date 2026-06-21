package io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.types.StoreOrderable;
import org.apache.derby.iapi.store.types.StoreRowLocation;
import org.apache.derby.iapi.types.DataValueDescriptor;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.impl.services.storetypes.EngineStoreRowLocationBridge;
import org.apache.derby.impl.store.access.heap.HeapRowLocation;

/**
 * Phase C19 review closeout smoke: exercise the typed planned route seam and
 * index read delegation without using SQL strings or regex routing.
 */
public final class StoragePhaseC19ReviewCloseoutSmoke {
    private static final String TABLE_NAME = "C19_REVIEW_CLOSEOUT";
    private static final String INDEX_NAME = "C19_REVIEW_CLOSEOUT_VALUE_IDX";

    private StoragePhaseC19ReviewCloseoutSmoke() {
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    public static void main(String[] args) throws Exception {
        verifyEngineRowLocationAdapterBehavior();

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
        requireAnyAccessPath("planned between range predicate");

        requireRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectWhereRange(
                        TABLE_NAME,
                        "value",
                        ">=",
                        "'bravo'"), owner),
                List.of("bravo", "charlie"));
        requireAnyAccessPath("planned lower-bound range predicate");

        requireRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectWhereRange(
                        TABLE_NAME,
                        "value",
                        "<",
                        "'charlie'"), owner),
                List.of("alpha", "bravo"));
        requireAnyAccessPath("planned upper-bound range predicate");

        requireRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectAll(
                        TABLE_NAME,
                        "value",
                        "DESC"), owner),
                List.of("charlie", "bravo", "alpha"));
        requireIndexAccessPath(INDEX_NAME, "planned ordered lookup");

        requireCount(execute(VersionedStorageSqlBridge.PlannedRoute.selectCount(TABLE_NAME), owner), 3);

        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.updateWhereEquals(
                        TABLE_NAME,
                        "value",
                        "'delta'",
                        "value",
                        "'bravo'"), owner),
                1L,
                "planned read-then-replace mutation");
        requireRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectWhereEquals(
                        TABLE_NAME,
                        "value",
                        "'delta'"), owner),
                List.of("delta"));
        requireNoRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectWhereEquals(
                        TABLE_NAME,
                        "value",
                        "'bravo'"), owner),
                "old value after planned replace");

        requireUpdateCount(execute(VersionedStorageSqlBridge.PlannedRoute.deleteWhereEquals(
                        TABLE_NAME,
                        "value",
                        "'alpha'"), owner),
                1L,
                "planned read-then-remove mutation");
        requireNoRows(execute(VersionedStorageSqlBridge.PlannedRoute.selectWhereEquals(
                        TABLE_NAME,
                        "value",
                        "'alpha'"), owner),
                "removed value after planned remove");
        requireCount(execute(VersionedStorageSqlBridge.PlannedRoute.selectCount(TABLE_NAME), owner), 2);

        System.out.println("storage_phase_c19_review_closeout table=" + TABLE_NAME + " index=" + INDEX_NAME);
        System.out.println("DelosDB Phase C19 review closeout smoke test passed.");
    }


    private static void verifyEngineRowLocationAdapterBehavior() throws Exception {
        RowLocation left = EngineStoreRowLocationBridge.newEngineRowLocation();
        RowLocation right = EngineStoreRowLocationBridge.newEngineRowLocation();
        HeapRowLocation raw = new HeapRowLocation();

        require(left instanceof StoreRowLocation, "engine adapter must expose the store row-location contract");
        require(right instanceof StoreRowLocation, "second engine adapter must expose the store row-location contract");

        StoreRowLocation leftStore = EngineStoreRowLocationBridge.requireStoreRowLocation(left.getObject());
        StoreRowLocation rightStore = EngineStoreRowLocationBridge.requireStoreRowLocation(right.getObject());
        require(leftStore instanceof HeapRowLocation, "adapter object must unwrap to HeapRowLocation");
        require(rightStore instanceof HeapRowLocation, "second adapter object must unwrap to HeapRowLocation");

        require(left.compare((DataValueDescriptor) right) == 0,
                "adapter.compare(adapter) must not throw and must compare equal for fresh locations");
        require(right.compare((DataValueDescriptor) left) == 0,
                "adapter comparison must be symmetric");
        require(left.compare(StoreOrderable.ORDER_OP_EQUALS, (DataValueDescriptor) right, false, false),
                "adapter ORDER_OP_EQUALS compare must succeed");
        require(left.equals(right), "adapter.equals(adapter) must be value-based");
        require(right.equals(left), "adapter equality must be symmetric");
        require(left.equals(raw), "adapter.equals(raw HeapRowLocation) must be value-based");
        require(raw.equals(left), "raw HeapRowLocation.equals(adapter) must be value-based");
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

    private static void requireNoRows(VersionedStorageSqlResult result, String label)
            throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException(label + " did not return rows");
        }
        try (ResultSet rows = result.resultSet()) {
            if (rows.next()) {
                throw new IllegalStateException(label + " unexpectedly returned row id="
                        + rows.getInt(1) + " value=" + rows.getString(2));
            }
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
