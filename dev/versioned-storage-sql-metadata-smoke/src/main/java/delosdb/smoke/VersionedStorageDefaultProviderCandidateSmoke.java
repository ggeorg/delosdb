package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Default-provider candidate smoke for delos_mvcc.
 *
 * <p>This is not a global default flip. Normal Derby CREATE TABLE remains heap
 * unless the process explicitly sets {@code delosdb.storage.defaultProvider}
 * to {@code delos_mvcc}. With that candidate property enabled, a plain CREATE
 * TABLE is routed through the same provider-owned MVCC bridge that previously
 * required {@code USING delos_mvcc}.</p>
 */
public final class VersionedStorageDefaultProviderCandidateSmoke {
    private static final String DEFAULT_PROVIDER_PROPERTY = "delosdb.storage.defaultProvider";
    private static final String MVCC_PROVIDER = "delos_mvcc";

    private VersionedStorageDefaultProviderCandidateSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        Path pageStorage = Path.of(databasePath + "-delos-mvcc-pages");
        String previousDefaultProvider = System.getProperty(DEFAULT_PROVIDER_PROPERTY);
        System.clearProperty(DEFAULT_PROVIDER_PROPERTY);
        SmokeUtils.loadEmbeddedDriver();

        try {
            assertPlainCreateTableStillUsesHeapWithoutCandidateProperty(databasePath);

            System.setProperty(DEFAULT_PROVIDER_PROPERTY, MVCC_PROVIDER);
            VersionedStorageSqlBridge.configurePageBackedStorage(pageStorage);
            assertPlainCreateTableCanUseMvccWithCandidateProperty(databasePath);
        } finally {
            restoreProperty(previousDefaultProvider);
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB MVCC default-provider candidate smoke test passed.");
    }

    private static void assertPlainCreateTableStillUsesHeapWithoutCandidateProperty(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table default_candidate_heap(id int)");
            statement.executeUpdate("insert into default_candidate_heap values (1)");
            SmokeUtils.assertEquals("1",
                    SmokeUtils.singleString(statement, "select cast(id as char(1)) from default_candidate_heap"),
                    "plain CREATE TABLE must still use normal Derby heap when default-provider candidate is not enabled");
        }
    }

    private static void assertPlainCreateTableCanUseMvccWithCandidateProperty(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table default_candidate_mvcc(id int primary key, name varchar(40))");
            statement.executeUpdate("insert into default_candidate_mvcc values (1, 'alpha')");
            assertRow(statement, 1, "alpha", "plain CREATE TABLE enters MVCC candidate path");
            statement.executeUpdate("create index default_candidate_mvcc_id_idx on default_candidate_mvcc(id)");
            statement.executeUpdate("update default_candidate_mvcc set name = 'beta' where id = 1");
            assertRow(statement, 1, "beta", "MVCC candidate update is visible before provider reopen");
        }

        VersionedStorageSqlBridge.reopenPageBackedStorage();
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertRow(statement, 1, "beta", "MVCC candidate update survives provider reopen");
            statement.executeUpdate("create index default_candidate_mvcc_id_idx_after_reopen on default_candidate_mvcc(id)");
            statement.executeUpdate("delete from default_candidate_mvcc where id = 1");
            assertCount(statement, 0, "MVCC candidate delete hides row before provider reopen");
        }

        VersionedStorageSqlBridge.reopenPageBackedStorage();
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertCount(statement, 0, "MVCC candidate delete survives provider reopen");
        }
    }

    private static void assertRow(Statement statement, int expectedId, String expectedName, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from default_candidate_mvcc where id = 1")) {
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
        try (ResultSet rs = statement.executeQuery("select count(*) from default_candidate_mvcc")) {
            if (!rs.next()) {
                throw new AssertionError(message + ": COUNT(*) returned no row");
            }
            SmokeUtils.assertEquals(String.valueOf(expected), String.valueOf(rs.getInt(1)), message);
        }
    }

    private static void restoreProperty(String previousValue) {
        if (previousValue == null) {
            System.clearProperty(DEFAULT_PROVIDER_PROPERTY);
        } else {
            System.setProperty(DEFAULT_PROVIDER_PROPERTY, previousValue);
        }
    }
}
