package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Phase C6 proof: keep the quarantined SQL bridge behavior working while the
 * INSERT implementation is shrunk to delegate the provider mutation through
 * VersionedStorageExecutionBridge instead of calling VersionedTable.insert
 * directly.
 */
public final class StoragePhaseC6SqlBridgeShrinkSmoke {
    private StoragePhaseC6SqlBridgeShrinkSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SqlBridgePlan plan = new SqlBridgePlan("C6_SQL_BRIDGE_SHRINK");
        requireUpdateCount(plan.execute("CREATE TABLE " + plan.tableName()
                + " (id INT, value VARCHAR(40)) USING delos_mvcc"), 0L, "create table");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (1, 'planned-alpha')"), 1L, "insert one row");
        requireRow(plan.execute("SELECT * FROM " + plan.tableName()), 1, "planned-alpha");

        System.out.println("storage_phase_c6_sql_bridge_shrink table=" + plan.tableName());
        System.out.println("DelosDB Phase C6 SQL bridge shrink smoke test passed.");
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

    private static void requireRow(VersionedStorageSqlResult result, int expectedId, String expectedValue)
            throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("select was not handled as rows by VersionedStorageSqlBridge");
        }
        try (ResultSet rows = result.resultSet()) {
            if (!rows.next()) {
                throw new IllegalStateException("expected one MVCC row");
            }
            int actualId = rows.getInt(1);
            String actualValue = rows.getString(2);
            if (actualId != expectedId || !expectedValue.equals(actualValue)) {
                throw new IllegalStateException("unexpected MVCC row: id=" + actualId + " value=" + actualValue);
            }
            if (rows.next()) {
                throw new IllegalStateException("expected exactly one MVCC row");
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
