package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase A4 smoke: the delos_mvcc SQL path writes committed rows into the
 * page-backed provider store and can reopen the provider without losing row
 * versions. Derby heap storage remains untouched; this is still not Derby WAL.
 */
public final class VersionedStorageDurableSqlSmoke {
    private VersionedStorageDurableSqlSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        Path pageStorage = Path.of(databasePath + "-delos-mvcc-pages");
        VersionedStorageSqlBridge.configurePageBackedStorage(pageStorage);
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table durable_mvcc(id int primary key, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("insert into durable_mvcc values (1, 'alpha')");
            assertCount(statement, 1, "inserted durable delos_mvcc row before provider reopen");
        }

        VersionedStorageSqlBridge.reopenPageBackedStorage();
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertTableRow(statement, 1, "alpha", "committed delos_mvcc insert survives provider reopen");
            statement.executeUpdate("create index durable_mvcc_id_idx on durable_mvcc(id)");
            statement.executeUpdate("update durable_mvcc set name = 'beta' where id = 1");
            assertTableRow(statement, 1, "beta", "committed delos_mvcc update is visible before provider reopen");
        }

        VersionedStorageSqlBridge.reopenPageBackedStorage();
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertTableRow(statement, 1, "beta", "committed delos_mvcc update survives provider reopen");
            statement.executeUpdate("create index durable_mvcc_id_idx_after_reopen on durable_mvcc(id)");
            statement.executeUpdate("delete from durable_mvcc where id = 1");
            assertCount(statement, 0, "committed delos_mvcc delete is visible before provider reopen");
        }

        VersionedStorageSqlBridge.reopenPageBackedStorage();
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertCount(statement, 0, "committed delos_mvcc delete survives provider reopen");
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB versioned-storage durable SQL smoke test passed.");
    }

    private static void assertTableRow(Statement statement, int expectedId, String expectedName, String message)
            throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from durable_mvcc")) {
            if (!rs.next()) {
                throw new AssertionError(message + ": no row returned");
            }
            SmokeUtils.assertEquals(String.valueOf(expectedId), String.valueOf(rs.getInt(1)), message + " id");
            SmokeUtils.assertEquals(expectedName, rs.getString(2), message + " name");
            if (rs.next()) {
                throw new AssertionError(message + ": unexpected extra row");
            }
        }
    }

    private static void assertCount(Statement statement, int expected, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select count(*) from durable_mvcc")) {
            if (!rs.next()) {
                throw new AssertionError(message + ": COUNT(*) returned no row");
            }
            SmokeUtils.assertEquals(String.valueOf(expected), String.valueOf(rs.getInt(1)), message);
        }
    }
}
