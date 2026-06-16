package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

/**
 * Phase 13 smoke for PostgreSQL-guided MVCC access-path observability.
 *
 * <p>The bridge now chooses between a provider-owned table scan and a
 * provider-owned index scan for simple equality predicates. The selected path
 * exposes table/index statistics so future optimizer integration has a concrete
 * contract.</p>
 */
public final class VersionedStorageOptimizerPathSmoke {
    private VersionedStorageOptimizerPathSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table versioned_storage_optimizer(id int, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("insert into versioned_storage_optimizer values (1, 'alpha')");
            statement.executeUpdate("insert into versioned_storage_optimizer values (2, 'beta')");
            statement.executeUpdate("insert into versioned_storage_optimizer values (3, 'beta')");
            statement.executeUpdate("insert into versioned_storage_optimizer values (4, 'gamma')");

            assertRows(statement,
                    "select * from versioned_storage_optimizer where name = 'beta'",
                    new Object[][]{{2, "beta"}, {3, "beta"}});
            VersionedStorageAccessPath tableScanPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.TABLE_SCAN,
                    tableScanPath.selectedAccessMethod(),
                    "unindexed equality predicate must fall back to provider-owned MVCC table scan");
            SmokeUtils.assertEquals("NAME", tableScanPath.predicateColumn(), "table-scan predicate column");
            SmokeUtils.assertEquals("4", String.valueOf(tableScanPath.visibleRowCount()), "table visible row count");
            SmokeUtils.assertEquals("4", String.valueOf(tableScanPath.estimatedTableScanCost()), "table-scan cost");

            statement.executeUpdate("create index vso_name_idx on versioned_storage_optimizer(name)");
            assertRows(statement,
                    "select * from versioned_storage_optimizer where name = 'beta'",
                    new Object[][]{{2, "beta"}, {3, "beta"}});
            VersionedStorageAccessPath indexPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                    indexPath.selectedAccessMethod(),
                    "indexed equality predicate should use provider-owned MVCC index scan");
            SmokeUtils.assertEquals("VSO_NAME_IDX", indexPath.selectedIndex(), "selected MVCC index");
            SmokeUtils.assertEquals("2", String.valueOf(indexPath.indexCandidateCount()), "index candidate count");
            SmokeUtils.assertEquals("2", String.valueOf(indexPath.indexVisibleMatchCount()), "index visible match count");
            if (indexPath.estimatedIndexLookupCost() > indexPath.estimatedTableScanCost()) {
                throw new AssertionError("Expected index lookup cost <= table scan cost: " + indexPath);
            }

            assertRows(statement,
                    "select * from versioned_storage_optimizer where name = 'missing'",
                    new Object[][]{});
            VersionedStorageAccessPath emptyIndexPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                    emptyIndexPath.selectedAccessMethod(),
                    "indexed missing-key predicate should still use provider-owned index scan");
            SmokeUtils.assertEquals("0", String.valueOf(emptyIndexPath.indexCandidateCount()), "missing-key candidate count");
            SmokeUtils.assertEquals("0", String.valueOf(emptyIndexPath.indexVisibleMatchCount()), "missing-key visible matches");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage optimizer path smoke test passed.");
    }

    private static VersionedStorageAccessPath requireLastPath() {
        return VersionedStorageSqlBridge.lastAccessPath()
                .orElseThrow(() -> new AssertionError("No delos_mvcc access path was recorded"));
    }

    private static void assertRows(Statement statement, String sql, Object[][] expectedRows) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            for (Object[] expected : expectedRows) {
                if (!rs.next()) {
                    throw new AssertionError("Expected row id=" + expected[0] + " from " + sql);
                }
                SmokeUtils.assertEquals(String.valueOf(expected[0]), String.valueOf(rs.getInt(1)), "delos_mvcc id");
                SmokeUtils.assertEquals((String) expected[1], rs.getString(2), "delos_mvcc name");
            }
            if (rs.next()) {
                throw new AssertionError("Unexpected extra row id=" + rs.getInt(1) + " from " + sql);
            }
        }
    }
}
