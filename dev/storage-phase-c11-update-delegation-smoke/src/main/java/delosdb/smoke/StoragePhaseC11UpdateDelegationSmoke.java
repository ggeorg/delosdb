package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Phase C11 proof: keep the quarantined SQL bridge UPDATE behavior working while
 * the UPDATE implementation delegates the provider mutation through
 * VersionedStorageExecutionBridge instead of calling VersionedTable.update
 * directly.
 */
public final class StoragePhaseC11UpdateDelegationSmoke {
    private StoragePhaseC11UpdateDelegationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SqlBridgePlan plan = new SqlBridgePlan("C11_UPDATE_DELEGATION");
        requireUpdateCount(plan.execute("CREATE TABLE " + plan.tableName()
                + " (id INT, value VARCHAR(40)) USING delos_mvcc"), 0L, "create table");
        requireUpdateCount(plan.execute("CREATE INDEX C11_UPDATE_VALUE_IDX ON "
                + plan.tableName() + "(value)"), 0L, "create value index");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (1, 'keep-me')"), 1L, "insert kept row");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (2, 'before-update')"), 1L, "insert updated row");
        requireUpdateCount(plan.execute("UPDATE " + plan.tableName()
                + " SET value = 'after-update' WHERE value = 'before-update'"), 1L, "update one row");
        requireSingleRow(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE value = 'after-update'"), 2, "after-update");
        requireNoRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE value = 'before-update'"));
        requireSingleRow(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE value = 'keep-me'"), 1, "keep-me");

        System.out.println("storage_phase_c11_update_delegation table=" + plan.tableName());
        System.out.println("DelosDB Phase C11 SQL UPDATE delegation smoke test passed.");
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
                throw new IllegalStateException("old MVCC row version is still visible: id="
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
