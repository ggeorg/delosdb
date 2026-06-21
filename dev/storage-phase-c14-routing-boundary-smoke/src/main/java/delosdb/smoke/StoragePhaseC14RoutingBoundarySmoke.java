package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase C14 proof: keep regex routing temporary, but isolate routing/classification
 * from routed-statement execution inside VersionedStorageSqlBridge.
 */
public final class StoragePhaseC14RoutingBoundarySmoke {
    private StoragePhaseC14RoutingBoundarySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SqlBridgePlan plan = new SqlBridgePlan("C14_ROUTING_BOUNDARY");
        requireUpdateCount(plan.execute("CREATE TABLE " + plan.tableName()
                + " (id INT, value VARCHAR(40)) USING delos_mvcc"), 0L, "create table");
        requireUpdateCount(plan.execute("CREATE INDEX c14_value_idx ON " + plan.tableName()
                + "(value)"), 0L, "create index");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (1, 'alpha')"), 1L, "insert alpha");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (2, 'bravo')"), 1L, "insert bravo");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (3, 'charlie')"), 1L, "insert charlie");

        requireRows(plan.execute("SELECT * FROM " + plan.tableName()), List.of("alpha", "bravo", "charlie"));
        requireRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE value = 'bravo'"), List.of("bravo"));
        requireRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE value >= 'bravo'"), List.of("bravo", "charlie"));
        requireRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE value BETWEEN 'alpha' AND 'bravo'"), List.of("alpha", "bravo"));
        requireOrderedRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " ORDER BY value DESC"), List.of("charlie", "bravo", "alpha"));
        requireCount(plan.execute("SELECT COUNT(*) FROM " + plan.tableName()), 3);

        requireUpdateCount(plan.execute("UPDATE " + plan.tableName()
                + " SET value = 'delta' WHERE value = 'bravo'"), 1L, "update bravo");
        requireRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE value = 'bravo'"), List.of());
        requireRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE value = 'delta'"), List.of("delta"));

        requireUpdateCount(plan.execute("DELETE FROM " + plan.tableName()
                + " WHERE value = 'alpha'"), 1L, "delete alpha");
        requireOrderedRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " ORDER BY value"), List.of("charlie", "delta"));
        requireCount(plan.execute("SELECT COUNT(*) FROM " + plan.tableName()), 2);

        System.out.println("storage_phase_c14_routing_boundary table=" + plan.tableName());
        System.out.println("DelosDB Phase C14 routing boundary smoke test passed.");
    }

    private static void requireUpdateCount(
            VersionedStorageSqlResult result,
            long expected,
            String label) {
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

    private static void requireRows(VersionedStorageSqlResult result, List<String> expectedValues)
            throws SQLException {
        List<String> actualValues = readValues(result);
        List<String> sortedActual = new ArrayList<>(actualValues);
        List<String> sortedExpected = new ArrayList<>(expectedValues);
        java.util.Collections.sort(sortedActual);
        java.util.Collections.sort(sortedExpected);
        if (!sortedActual.equals(sortedExpected)) {
            throw new IllegalStateException("rows expected=" + expectedValues + " actual=" + actualValues);
        }
    }

    private static void requireOrderedRows(VersionedStorageSqlResult result, List<String> expectedValues)
            throws SQLException {
        List<String> actualValues = readValues(result);
        if (!actualValues.equals(expectedValues)) {
            throw new IllegalStateException("ordered rows expected=" + expectedValues + " actual=" + actualValues);
        }
    }

    private static List<String> readValues(VersionedStorageSqlResult result) throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("select was not handled as rows by VersionedStorageSqlBridge");
        }
        List<String> actualValues = new ArrayList<>();
        try (ResultSet rows = result.resultSet()) {
            while (rows.next()) {
                actualValues.add(rows.getString(2));
            }
        }
        return actualValues;
    }

    private static void requireCount(VersionedStorageSqlResult result, int expectedCount)
            throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("count select was not handled as rows by VersionedStorageSqlBridge");
        }
        try (ResultSet rows = result.resultSet()) {
            if (!rows.next()) {
                throw new IllegalStateException("expected one COUNT row");
            }
            int actualCount = rows.getInt(1);
            if (actualCount != expectedCount) {
                throw new IllegalStateException("COUNT expected=" + expectedCount + " actual=" + actualCount);
            }
            if (rows.next()) {
                throw new IllegalStateException("expected exactly one COUNT row");
            }
        }
    }

    private record SqlBridgePlan(String tableName) {
        private SqlBridgePlan {
            tableName = Objects.requireNonNull(tableName, "tableName").trim();
            if (tableName.isEmpty()) {
                throw new IllegalArgumentException("tableName must not be blank");
            }
        }

        private VersionedStorageSqlResult execute(String sql) throws SQLException {
            return VersionedStorageSqlBridge.tryExecute(sql, this, true);
        }
    }
}
