package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Phase C9 proof: keep the quarantined SQL bridge DELETE behavior working while
 * the DELETE implementation delegates the provider mutation through
 * VersionedStorageExecutionBridge instead of calling VersionedTable.delete
 * directly.
 */
public final class StoragePhaseC9DeleteDelegationSmoke {
    private StoragePhaseC9DeleteDelegationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SqlBridgePlan plan = new SqlBridgePlan("C9_DELETE_DELEGATION");
        requireUpdateCount(plan.execute("CREATE TABLE " + plan.tableName()
                + " (id INT, value VARCHAR(40)) USING delos_mvcc"), 0L, "create table");
        requireUpdateCount(plan.execute("CREATE INDEX C9_DELETE_VALUE_IDX ON "
                + plan.tableName() + "(value)"), 0L, "create value index");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (1, 'keep-me')"), 1L, "insert kept row");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (2, 'delete-me')"), 1L, "insert deleted row");
        requireUpdateCount(plan.execute("DELETE FROM " + plan.tableName()
                + " WHERE value = 'delete-me'"), 1L, "delete one row");
        requireSingleRow(plan.execute("SELECT * FROM " + plan.tableName()), 1, "keep-me");
        requireNoRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE value = 'delete-me'"));

        System.out.println("storage_phase_c9_delete_delegation table=" + plan.tableName());
        System.out.println("DelosDB Phase C9 SQL DELETE delegation smoke test passed.");
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

    private static void requireSingleRow(VersionedStorageSqlResult result, int expectedId, String expectedValue)
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

    private static void requireNoRows(VersionedStorageSqlResult result) throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("indexed select was not handled as rows by VersionedStorageSqlBridge");
        }
        try (ResultSet rows = result.resultSet()) {
            if (rows.next()) {
                throw new IllegalStateException("deleted MVCC row is still visible: id="
                        + rows.getInt(1) + " value=" + rows.getString(2));
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
