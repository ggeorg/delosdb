package delosdb.smoke;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase 4 smoke for the experimental delos_mvcc SQL table-scan path.
 *
 * <p>This is intentionally narrow: CREATE TABLE, INSERT, SELECT table scan,
 * and COUNT(*) through the versioned-storage provider. Derby-compatible heap
 * tables continue to use the inherited execution path.</p>
 */
public final class VersionedStorageSqlMetadataSmoke {
    private VersionedStorageSqlMetadataSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table versioned_storage_heap(id int) using heap");
            statement.executeUpdate("insert into versioned_storage_heap values (1)");
            SmokeUtils.assertEquals("1",
                    SmokeUtils.singleString(statement, "select cast(id as char(1)) from versioned_storage_heap"),
                    "built-in heap storage remains executable");

            statement.executeUpdate("create table versioned_storage_mvcc(id int, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("insert into versioned_storage_mvcc values (1, 'alpha')");
            statement.executeUpdate("insert into versioned_storage_mvcc values (2, 'beta')");

            try (ResultSet rs = statement.executeQuery("select * from versioned_storage_mvcc")) {
                assertNext(rs, 1, "alpha");
                assertNext(rs, 2, "beta");
                if (rs.next()) {
                    throw new AssertionError("Unexpected extra row from delos_mvcc table scan");
                }
            }

            try (ResultSet rs = statement.executeQuery("select count(*) from versioned_storage_mvcc")) {
                if (!rs.next()) {
                    throw new AssertionError("COUNT(*) returned no row for delos_mvcc table");
                }
                SmokeUtils.assertEquals("2", String.valueOf(rs.getInt(1)), "delos_mvcc COUNT(*)");
            }

            statement.executeUpdate("drop table versioned_storage_heap");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage SQL table-scan smoke test passed.");
    }

    private static void assertNext(ResultSet rs, int expectedId, String expectedName) throws Exception {
        if (!rs.next()) {
            throw new AssertionError("Expected row id=" + expectedId);
        }
        SmokeUtils.assertEquals(String.valueOf(expectedId), String.valueOf(rs.getInt(1)), "delos_mvcc id");
        SmokeUtils.assertEquals(expectedName, rs.getString(2), "delos_mvcc name");
    }
}
