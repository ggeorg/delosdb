package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Phase C7 proof: keep the quarantined SQL bridge SELECT behavior working while
 * the SELECT table-scan implementation is shrunk to delegate the provider scan
 * through VersionedStorageExecutionBridge instead of opening VersionedTable scans
 * directly for the simple SELECT-all path.
 */
public final class StoragePhaseC7SelectBridgeShrinkSmoke {
    private StoragePhaseC7SelectBridgeShrinkSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SqlBridgePlan plan = new SqlBridgePlan("C7_SELECT_BRIDGE_SHRINK");
        requireUpdateCount(plan.execute("CREATE TABLE " + plan.tableName()
                + " (id INT, value VARCHAR(40)) USING delos_mvcc"), 0L, "create table");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (1, 'select-alpha')"), 1L, "insert alpha");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (2, 'select-beta')"), 1L, "insert beta");
        requireRows(plan.execute("SELECT * FROM " + plan.tableName()),
                List.of("1:select-alpha", "2:select-beta"));

        System.out.println("storage_phase_c7_select_bridge_shrink table=" + plan.tableName());
        System.out.println("DelosDB Phase C7 SELECT bridge shrink smoke test passed.");
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

    private static void requireRows(VersionedStorageSqlResult result, List<String> expected)
            throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("select was not handled as rows by VersionedStorageSqlBridge");
        }
        List<String> actual = new ArrayList<>();
        try (ResultSet rows = result.resultSet()) {
            while (rows.next()) {
                actual.add(rows.getInt(1) + ":" + rows.getString(2));
            }
        }
        if (!expected.equals(actual)) {
            throw new IllegalStateException("unexpected MVCC rows: expected=" + expected + " actual=" + actual);
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
