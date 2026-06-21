package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlResult;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Phase C12 proof: keep temporary SQL regex routing, but make SQL bridge
 * table-scan and stats read operations use VersionedStorageExecutionBridge
 * instead of calling VersionedTable.openScan/stats directly.
 */
public final class StoragePhaseC12ReadDelegationCloseoutSmoke {
    private StoragePhaseC12ReadDelegationCloseoutSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SqlBridgePlan plan = new SqlBridgePlan("C12_READ_DELEGATION");
        requireUpdateCount(plan.execute("CREATE TABLE " + plan.tableName()
                + " (id INT, value VARCHAR(40)) USING delos_mvcc"), 0L, "create table");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (1, 'alpha')"), 1L, "insert alpha");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (2, 'bravo')"), 1L, "insert bravo");
        requireUpdateCount(plan.execute("INSERT INTO " + plan.tableName()
                + " VALUES (3, 'charlie')"), 1L, "insert charlie");

        requireSingleRow(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE id = 2"), 2, "bravo");
        requireRows(plan.execute("SELECT * FROM " + plan.tableName()
                + " WHERE id >= 2"), 2);
        requireCount(plan.execute("SELECT COUNT(*) FROM " + plan.tableName()), 3);

        System.out.println("storage_phase_c12_read_delegation_closeout table=" + plan.tableName());
        System.out.println("DelosDB Phase C12 read delegation closeout smoke test passed.");
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

    private static void requireRows(VersionedStorageSqlResult result, int expectedCount)
            throws SQLException {
        if (result == null || !result.returnsRows()) {
            throw new IllegalStateException("range select was not handled as rows by VersionedStorageSqlBridge");
        }
        int count = 0;
        try (ResultSet rows = result.resultSet()) {
            while (rows.next()) {
                count++;
            }
        }
        if (count != expectedCount) {
            throw new IllegalStateException("range select count expected=" + expectedCount + " actual=" + count);
        }
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
