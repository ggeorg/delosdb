package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

/**
 * Phase 16 smoke for bounded ORDER BY over provider-owned ordered MVCC indexes.
 */
public final class VersionedStorageOrderByLimitSmoke {
    private VersionedStorageOrderByLimitSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table versioned_storage_order_limit(id int, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("insert into versioned_storage_order_limit values (1, 'delta')");
            statement.executeUpdate("insert into versioned_storage_order_limit values (2, 'alpha')");
            statement.executeUpdate("insert into versioned_storage_order_limit values (3, 'charlie')");
            statement.executeUpdate("insert into versioned_storage_order_limit values (4, 'bravo')");

            assertRows(statement,
                    "select * from versioned_storage_order_limit order by name limit 2",
                    new Object[][]{{2, "alpha"}, {4, "bravo"}});
            VersionedStorageAccessPath tableLimitPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.TABLE_SCAN,
                    tableLimitPath.selectedAccessMethod(),
                    "ORDER BY LIMIT without provider-owned index should use table scan plus bridge sort");
            SmokeUtils.assertEquals("select-order-limit", tableLimitPath.operation(), "ORDER BY LIMIT operation");

            statement.executeUpdate("create index vsol_name_idx on versioned_storage_order_limit(name)");
            assertRows(statement,
                    "select * from versioned_storage_order_limit order by name limit 2",
                    new Object[][]{{2, "alpha"}, {4, "bravo"}});
            VersionedStorageAccessPath indexLimitPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                    indexLimitPath.selectedAccessMethod(),
                    "ORDER BY LIMIT with provider-owned ordered index should use bounded index scan");
            SmokeUtils.assertEquals("select-order-limit", indexLimitPath.operation(), "indexed ORDER BY LIMIT operation");
            SmokeUtils.assertEquals("VSOL_NAME_IDX", indexLimitPath.selectedIndex(), "selected ORDER BY LIMIT index");
            SmokeUtils.assertEquals("4", String.valueOf(indexLimitPath.indexVisibleMatchCount()),
                    "full index stats remain visible even when the returned rowset is bounded");

            assertRows(statement,
                    "select * from versioned_storage_order_limit order by name desc limit 2",
                    new Object[][]{{1, "delta"}, {3, "charlie"}});
            VersionedStorageAccessPath descLimitPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                    descLimitPath.selectedAccessMethod(),
                    "ORDER BY DESC LIMIT should use ordered index path");
            SmokeUtils.assertEquals("select-order-limit-desc", descLimitPath.operation(), "ORDER BY DESC LIMIT operation");

            assertRows(statement,
                    "select * from versioned_storage_order_limit limit 2",
                    new Object[][]{{1, "delta"}, {2, "alpha"}});
            VersionedStorageAccessPath tableOnlyLimitPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.TABLE_SCAN,
                    tableOnlyLimitPath.selectedAccessMethod(),
                    "LIMIT without ORDER BY remains a bounded table scan");
            SmokeUtils.assertEquals("select-limit", tableOnlyLimitPath.operation(), "plain LIMIT operation");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage ORDER BY LIMIT smoke test passed.");
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
