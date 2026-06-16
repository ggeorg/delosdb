package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

/**
 * Phase 14 smoke for ordered provider-owned MVCC index range scans.
 */
public final class VersionedStorageIndexRangeSmoke {
    private VersionedStorageIndexRangeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table versioned_storage_range(id int, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("insert into versioned_storage_range values (1, 'alpha')");
            statement.executeUpdate("insert into versioned_storage_range values (2, 'beta')");
            statement.executeUpdate("insert into versioned_storage_range values (3, 'beta')");
            statement.executeUpdate("insert into versioned_storage_range values (4, 'gamma')");
            statement.executeUpdate("insert into versioned_storage_range values (5, 'omega')");

            assertRows(statement,
                    "select * from versioned_storage_range where name >= 'beta'",
                    new Object[][]{{2, "beta"}, {3, "beta"}, {4, "gamma"}, {5, "omega"}});
            VersionedStorageAccessPath tableScanPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.TABLE_SCAN,
                    tableScanPath.selectedAccessMethod(),
                    "unindexed range predicate must use provider-owned table scan");
            SmokeUtils.assertEquals("select-range", tableScanPath.operation(), "range table-scan operation");

            statement.executeUpdate("create index vsr_name_idx on versioned_storage_range(name)");

            assertRows(statement,
                    "select * from versioned_storage_range where name between 'beta' and 'gamma'",
                    new Object[][]{{2, "beta"}, {3, "beta"}, {4, "gamma"}});
            VersionedStorageAccessPath betweenPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                    betweenPath.selectedAccessMethod(),
                    "indexed BETWEEN predicate should use ordered provider-owned index scan");
            SmokeUtils.assertEquals("VSR_NAME_IDX", betweenPath.selectedIndex(), "selected range index");
            SmokeUtils.assertEquals("3", String.valueOf(betweenPath.indexCandidateCount()), "BETWEEN candidate count");
            SmokeUtils.assertEquals("3", String.valueOf(betweenPath.indexVisibleMatchCount()), "BETWEEN visible match count");

            assertRows(statement,
                    "select * from versioned_storage_range where name < 'gamma'",
                    new Object[][]{{1, "alpha"}, {2, "beta"}, {3, "beta"}});
            VersionedStorageAccessPath lessThanPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                    lessThanPath.selectedAccessMethod(),
                    "indexed < predicate should use ordered provider-owned index scan");

            assertRows(statement,
                    "select * from versioned_storage_range where name > 'zeta'",
                    new Object[][]{});
            VersionedStorageAccessPath emptyPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                    emptyPath.selectedAccessMethod(),
                    "empty indexed range should still use provider-owned index scan");
            SmokeUtils.assertEquals("0", String.valueOf(emptyPath.indexCandidateCount()), "empty range candidates");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage ordered-index range smoke test passed.");
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
