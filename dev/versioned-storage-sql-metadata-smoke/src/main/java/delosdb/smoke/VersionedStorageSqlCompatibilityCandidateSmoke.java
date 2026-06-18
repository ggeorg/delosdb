package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageAccessPath;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * A52 closeout smoke for the guarded delos_mvcc SQL compatibility candidate path.
 *
 * <p>This intentionally stays inside the existing experimental bridge surface:
 * the table is created with {@code USING delos_mvcc}, the page-backed provider is
 * configured explicitly, and the normal Derby heap default remains untouched.</p>
 */
public final class VersionedStorageSqlCompatibilityCandidateSmoke {
    private static final String TABLE_NAME = "sql_compat_candidate_mvcc";
    private static final String ID_INDEX = "SCC_ID_IDX";
    private static final String NAME_INDEX = "SCC_NAME_IDX";

    private VersionedStorageSqlCompatibilityCandidateSmoke() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected database path argument");
        }

        String databasePath = args[0];
        Path pageStorage = Path.of(databasePath + "-delos-mvcc-pages");
        VersionedStorageSqlBridge.configurePageBackedStorage(pageStorage);
        SmokeUtils.loadEmbeddedDriver();

        try {
            createCandidateTableAndSeedRows(databasePath);
            assertRollbackKeepsCommittedState(databasePath);
            assertCommitUpdatesCandidateState(databasePath);
            assertCandidateStateSurvivesProviderReopen(databasePath);
        } finally {
            SmokeUtils.shutdown(databasePath);
        }

        System.out.println("DelosDB MVCC SQL compatibility candidate smoke test passed.");
    }

    private static void createCandidateTableAndSeedRows(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table " + TABLE_NAME + "(id int primary key, name varchar(40)) using delos_mvcc");
            statement.executeUpdate("create index " + ID_INDEX + " on " + TABLE_NAME + "(id)");
            statement.executeUpdate("create index " + NAME_INDEX + " on " + TABLE_NAME + "(name)");
            statement.executeUpdate("insert into " + TABLE_NAME + " values (1, 'alpha')");
            statement.executeUpdate("insert into " + TABLE_NAME + " values (2, 'beta')");
            statement.executeUpdate("insert into " + TABLE_NAME + " values (3, 'gamma')");
            statement.executeUpdate("insert into " + TABLE_NAME + " values (4, 'delta')");

            assertCount(statement, 4, "seeded candidate rows");
            assertRowById(statement, 2, "beta", "seeded row is visible by primary-key lookup");
            assertRows(statement,
                    "select * from " + TABLE_NAME + " where name between 'beta' and 'gamma'",
                    new Object[][]{{2, "beta"}, {4, "delta"}, {3, "gamma"}});
            assertLastPath(NAME_INDEX, "NAME", "indexed range path after seed");
            assertRows(statement,
                    "select * from " + TABLE_NAME + " order by name",
                    new Object[][]{{1, "alpha"}, {2, "beta"}, {4, "delta"}, {3, "gamma"}});
            assertLastPath(NAME_INDEX, "NAME", "ORDER BY path after seed");
        }
    }

    private static void assertRollbackKeepsCommittedState(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            statement.executeUpdate("update " + TABLE_NAME + " set name = 'rollback-beta' where id = 2");
            statement.executeUpdate("delete from " + TABLE_NAME + " where id = 3");
            statement.executeUpdate("insert into " + TABLE_NAME + " values (5, 'rollback-new')");
            assertRowById(statement, 2, "rollback-beta", "own uncommitted update is visible before rollback");
            assertNoRowById(statement, 3, "own uncommitted delete is visible before rollback");
            assertRowById(statement, 5, "rollback-new", "own uncommitted insert is visible before rollback");
            connection.rollback();
        }

        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertCount(statement, 4, "rollback restored committed row count");
            assertRowById(statement, 2, "beta", "rollback restored updated row");
            assertRowById(statement, 3, "gamma", "rollback restored deleted row");
            assertNoRowById(statement, 5, "rollback removed inserted row");
        }
    }

    private static void assertCommitUpdatesCandidateState(String databasePath) throws Exception {
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            statement.executeUpdate("update " + TABLE_NAME + " set name = 'bravo' where id = 2");
            statement.executeUpdate("delete from " + TABLE_NAME + " where id = 4");
            statement.executeUpdate("insert into " + TABLE_NAME + " values (5, 'omega')");
            assertRowById(statement, 2, "bravo", "own committed-later update is visible before commit");
            assertNoRowById(statement, 4, "own committed-later delete is visible before commit");
            assertRowById(statement, 5, "omega", "own committed-later insert is visible before commit");
            connection.commit();
        }

        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertCommittedCandidateState(statement, "after commit");
        }
    }

    private static void assertCandidateStateSurvivesProviderReopen(String databasePath) throws Exception {
        VersionedStorageSqlBridge.reopenPageBackedStorage();
        try (Connection connection = SmokeUtils.connect(databasePath, false);
             Statement statement = connection.createStatement()) {
            assertCommittedCandidateState(statement, "after provider reopen");
        }
    }

    private static void assertCommittedCandidateState(Statement statement, String phase) throws Exception {
        assertCount(statement, 4, phase + " row count");
        assertRowById(statement, 1, "alpha", phase + " id=1");
        assertRowById(statement, 2, "bravo", phase + " id=2 updated row");
        assertRowById(statement, 3, "gamma", phase + " id=3 preserved row");
        assertNoRowById(statement, 4, phase + " id=4 deleted row");
        assertRowById(statement, 5, "omega", phase + " id=5 inserted row");

        assertRows(statement,
                "select * from " + TABLE_NAME + " where name between 'bravo' and 'omega'",
                new Object[][]{{2, "bravo"}, {3, "gamma"}, {5, "omega"}});
        assertLastPath(NAME_INDEX, "NAME", phase + " range index path");

        assertRows(statement,
                "select * from " + TABLE_NAME + " order by name",
                new Object[][]{{1, "alpha"}, {2, "bravo"}, {3, "gamma"}, {5, "omega"}});
        assertLastPath(NAME_INDEX, "NAME", phase + " ORDER BY index path");
    }

    private static void assertRowById(Statement statement, int expectedId, String expectedName, String message) throws Exception {
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
        assertLastPath(ID_INDEX, "ID", message + " access path");
    }

    private static void assertNoRowById(Statement statement, int id, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select * from " + TABLE_NAME + " where id = " + id)) {
            if (rs.next()) {
                throw new AssertionError(message + ": unexpected row id=" + rs.getInt(1));
            }
        }
        assertLastPath(ID_INDEX, "ID", message + " access path");
    }

    private static void assertCount(Statement statement, int expected, String message) throws Exception {
        try (ResultSet rs = statement.executeQuery("select count(*) from " + TABLE_NAME)) {
            if (!rs.next()) {
                throw new AssertionError(message + ": COUNT(*) returned no row");
            }
            SmokeUtils.assertEquals(String.valueOf(expected), String.valueOf(rs.getInt(1)), message);
            if (rs.next()) {
                throw new AssertionError(message + ": COUNT(*) returned extra row");
            }
        }
    }

    private static void assertRows(Statement statement, String sql, Object[][] expectedRows) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            for (Object[] expected : expectedRows) {
                if (!rs.next()) {
                    throw new AssertionError("Expected row id=" + expected[0] + " from " + sql);
                }
                SmokeUtils.assertEquals(String.valueOf(expected[0]), String.valueOf(rs.getInt(1)), "delos_mvcc id from " + sql);
                SmokeUtils.assertEquals((String) expected[1], rs.getString(2), "delos_mvcc name from " + sql);
            }
            if (rs.next()) {
                throw new AssertionError("Unexpected extra row id=" + rs.getInt(1) + " from " + sql);
            }
        }
    }

    private static void assertLastPath(String expectedIndex, String expectedColumn, String message) {
        VersionedStorageAccessPath accessPath = VersionedStorageSqlBridge.lastAccessPath()
                .orElseThrow(() -> new AssertionError(message + ": no MVCC access path recorded"));
        SmokeUtils.assertEquals(VersionedStorageAccessPath.INDEX_SCAN,
                accessPath.selectedAccessMethod(),
                message + " access method");
        SmokeUtils.assertEquals(expectedIndex, accessPath.selectedIndex(), message + " selected index");
        SmokeUtils.assertEquals(expectedColumn, accessPath.predicateColumn(), message + " predicate/order column");
    }
}
