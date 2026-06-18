package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * MVCC-17 matrix for the guarded default-provider candidate path.
 *
 * <p>This is still not a global default-store switch. It proves that normal
 * Derby heap remains the default without a process property, while a process
 * that explicitly sets {@code delosdb.storage.defaultProvider=delos_mvcc} can
 * run bare SQL through the page-backed provider-owned MVCC path and recover the
 * updated/deleted visibility state plus rebuilt provider-owned index metadata.</p>
 */
public final class VersionedStorageDefaultProviderCandidateMatrixSmoke {
    private static final String DEFAULT_PROVIDER_PROPERTY = "delosdb.storage.defaultProvider";
    private static final String MVCC_PROVIDER = "delos_mvcc";
    private static final String TABLE_NAME = "default_candidate_matrix_mvcc";
    private static final String INDEX_NAME = "DPCM_ID_IDX";

    private VersionedStorageDefaultProviderCandidateMatrixSmoke() {
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
            assertCandidatePathBeforeAndAfterProviderRecovery(databasePath);
        } finally {
            restoreProperty(previousDefaultProvider);
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB MVCC default-provider candidate matrix smoke test passed.");
    }

    private static void assertPlainCreateTableStillUsesHeapWithoutCandidateProperty(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table default_candidate_matrix_heap(id int primary key, name varchar(20))");
            statement.executeUpdate("insert into default_candidate_matrix_heap values (1, 'heap')");
            SmokeUtils.assertEquals("heap",
                    SmokeUtils.singleString(statement, "select name from default_candidate_matrix_heap where id = 1"),
                    "plain CREATE TABLE must remain Derby heap when default-provider candidate is disabled");
        }
    }

    private static void assertCandidatePathBeforeAndAfterProviderRecovery(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + TABLE_NAME + "(id int primary key, name varchar(20))");
            statement.executeUpdate("insert into " + TABLE_NAME + " values (1, 'alpha')");
            statement.executeUpdate("insert into " + TABLE_NAME + " values (2, 'delete-me')");
            statement.executeUpdate("create index " + INDEX_NAME + " on " + TABLE_NAME + "(id)");

            assertRowByPrimaryKey(statement, 1, "alpha", "inserted MVCC row is visible through primary-key lookup");
            statement.executeUpdate("update " + TABLE_NAME + " set name = 'beta' where id = 1");
            assertRowByPrimaryKey(statement, 1, "beta", "updated MVCC row exposes the newest visible version");
            statement.executeUpdate("delete from " + TABLE_NAME + " where id = 2");
            assertNoRowByPrimaryKey(statement, 2, "deleted MVCC row is hidden before provider reopen");
            assertCount(statement, 1, "one updated, not-deleted MVCC row remains before provider reopen");
        }

        VersionedStorageSqlBridge.reopenPageBackedStorage();
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertRowByPrimaryKey(statement, 1, "beta", "updated MVCC row survives provider reopen");
            assertNoRowByPrimaryKey(statement, 2, "deleted MVCC row remains hidden after provider reopen");
            assertCount(statement, 1, "only the updated, not-deleted MVCC row remains after provider reopen");
        }
    }

    private static void assertRowByPrimaryKey(Statement statement, int expectedId, String expectedName, String message)
            throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from " + TABLE_NAME + " where id = " + expectedId)) {
            if (!rs.next()) {
                throw new AssertionError(message + ": no row returned");
            }
            SmokeUtils.assertEquals(String.valueOf(expectedId), String.valueOf(rs.getInt(1)), message + " id");
            SmokeUtils.assertEquals(expectedName, rs.getString(2), message + " name");
            if (rs.next()) {
                throw new AssertionError(message + ": unexpected extra row");
            }
        }
        assertLastPathUsesPrimaryKeyIndex(message);
    }

    private static void assertNoRowByPrimaryKey(Statement statement, int id, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from " + TABLE_NAME + " where id = " + id)) {
            if (rs.next()) {
                throw new AssertionError(message + ": unexpected row id=" + rs.getInt(1));
            }
        }
        assertLastPathUsesPrimaryKeyIndex(message);
    }

    private static void assertLastPathUsesPrimaryKeyIndex(String message) {
        VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                .orElseThrow(() -> new AssertionError(message + ": no MVCC access path recorded"));
        SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                accessPath.selectedAccessMethod(),
                message + " access method");
        SmokeUtils.assertEquals(INDEX_NAME, accessPath.selectedIndex(), message + " selected index");
        SmokeUtils.assertEquals("ID", accessPath.predicateColumn(), message + " predicate column");
    }

    private static void assertCount(Statement statement, int expected, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select count(*) from " + TABLE_NAME)) {
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
