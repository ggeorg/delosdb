package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

/**
 * Phase 15 smoke for ORDER BY over provider-owned ordered MVCC indexes.
 */
public final class VersionedStorageOrderByPathSmoke {
    private VersionedStorageOrderByPathSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table versioned_storage_order(id int, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("insert into versioned_storage_order values (1, 'gamma')");
            statement.executeUpdate("insert into versioned_storage_order values (2, 'alpha')");
            statement.executeUpdate("insert into versioned_storage_order values (3, 'beta')");

            assertRows(statement,
                    "select * from versioned_storage_order order by name",
                    new Object[][]{{2, "alpha"}, {3, "beta"}, {1, "gamma"}});
            VersionedStorageAccessPath sortPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.TABLE_SCAN,
                    sortPath.selectedAccessMethod(),
                    "ORDER BY without provider-owned index should use table scan plus bridge sort");
            SmokeUtils.assertEquals("select-order", sortPath.operation(), "ORDER BY operation");

            statement.executeUpdate("create index vso_order_name_idx on versioned_storage_order(name)");
            assertRows(statement,
                    "select * from versioned_storage_order order by name",
                    new Object[][]{{2, "alpha"}, {3, "beta"}, {1, "gamma"}});
            VersionedStorageAccessPath indexOrderPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                    indexOrderPath.selectedAccessMethod(),
                    "ORDER BY with provider-owned ordered index should use index order");
            SmokeUtils.assertEquals("VSO_ORDER_NAME_IDX", indexOrderPath.selectedIndex(), "selected ORDER BY index");
            SmokeUtils.assertEquals("3", String.valueOf(indexOrderPath.indexVisibleMatchCount()), "ORDER BY visible match count");

            statement.executeUpdate("update versioned_storage_order set name = 'aardvark' where name = 'gamma'");
            statement.executeUpdate("delete from versioned_storage_order where name = 'beta'");

            assertRows(statement,
                    "select * from versioned_storage_order order by name desc",
                    new Object[][]{{2, "alpha"}, {1, "aardvark"}});
            VersionedStorageAccessPath descPath = requireLastPath();
            SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                    descPath.selectedAccessMethod(),
                    "ORDER BY DESC should reuse provider-owned ordered index in reverse order");
            SmokeUtils.assertEquals("select-order-desc", descPath.operation(), "ORDER BY DESC operation");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage ORDER BY path smoke test passed.");
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
